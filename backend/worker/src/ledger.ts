import { ApiError, plain, type Usage } from './protocol';
import { DAILY_LIMIT, attemptCeiling, billingPolicy, estimateCost, hasBudget, serverDay, snapshot, validateStats, zeroStats, type BillingPolicy, type QuotaSnapshot, type RateCard, type Stats, type TokenReport } from './accounting';
import type { Bindings } from './env';

export interface SavedResponse {status:number;body:unknown}
export interface Job {fingerprint:string;createdAt:number;response:SavedResponse|null;billing?:{day:string;settled:boolean};credentialBinding?:string}
export interface Attempt {index:number;startedAt:number;finishedAt:number|null;state:'STARTED'|'KNOWN'|'UNKNOWN';httpStatus:number|null;errorCode:string|null;model:'deepseek-flash';currency:'CNY';tokens:TokenReport|null;estimatedMicros:string|null;ceilingMicros:string;rateCard:RateCard}
interface Meta {version:1;legacyReviewRequired:boolean;offsetMinutes:number;days:string[]}
const META='billing:meta',TOTAL='billing:total';
const dayKey=(day:string)=>'billing:day:'+day;
export const jobKey=(id:string)=>'job:'+id;
export const attemptKey=(id:string,index:number)=>'billing:attempt:'+id+':'+index;
const unavailable=()=>new ApiError('STORAGE_UNAVAILABLE',503,true);
const add=(a:string,b:string)=>{const v=BigInt(a)+BigInt(b);if(v<0n)throw unavailable();return v.toString();};
const apply=(s:Stats,fn:(s:Stats)=>void)=>{fn(s);validateStats(s);};
export class Ledger {
  constructor(private storage:DurableObjectStorage,private env:Bindings,private offsetMinutes:number){}
  // Additive migration. Old responses and identity fingerprints are never removed.
  async ensure(tx:DurableObjectTransaction,now:number):Promise<Meta> {
    const meta=await tx.get<Meta>(META);if(meta){if(meta.version!==1||meta.offsetMinutes!==this.offsetMinutes||typeof meta.legacyReviewRequired!=='boolean'||!Array.isArray(meta.days)||meta.days.some(d=>typeof d!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(d)))throw unavailable();return meta;}
    const old=await tx.list<Job>({prefix:'job:',limit:1001});if(old.size>1000)throw new ApiError('BILLING_REVIEW_REQUIRED',503);
    const total=zeroStats(),days=new Map<string,Stats>();
    for(const job of old.values()){
      if(!plain(job)||!Number.isSafeInteger(job.createdAt))throw unavailable();
      const day=serverDay(job.createdAt,this.offsetMinutes).day,s=days.get(day)??zeroStats();
      const body=job.response?.body,u=plain(body)&&plain(body.usage)?body.usage:null;
      const ok=job.response?.status===200&&plain(body)&&body.status==='success';
      for(const stats of [s,total]){
        if(job.response){if(ok)stats.successes++;else stats.failedTasks++;}else stats.reservedTasks++;
        if(u&&typeof u.httpRequests==='number'&&Number.isSafeInteger(u.httpRequests)&&u.httpRequests>=0){stats.httpRequests+=u.httpRequests;stats.retries+=typeof u.retries==='number'?u.retries:0;
          if(u.complete===true&&typeof u.promptTokens==='number'&&typeof u.completionTokens==='number'){stats.knownPromptTokens+=u.promptTokens;stats.knownCompletionTokens+=u.completionTokens;}else stats.unknownUsageAttempts+=u.httpRequests;
        }
      }days.set(day,s);
    }
    const next:Meta={version:1,legacyReviewRequired:old.size>0,offsetMinutes:this.offsetMinutes,days:[...days.keys()]};
    await tx.put({[META]:next,[TOTAL]:total});for(const [day,s] of days)await tx.put(dayKey(day),validateStats(s));return next;
  }
  async state(tx:DurableObjectTransaction,now:number,day=serverDay(now,this.offsetMinutes).day){
    const meta=await this.ensure(tx,now),total=await tx.get<Stats>(TOTAL);if(!total)throw unavailable();
    let daily=await tx.get<Stats>(dayKey(day));
    if(!daily){if(meta.days.includes(day))throw unavailable();daily=zeroStats();meta.days.push(day);await tx.put({[META]:meta,[dayKey(day)]:daily});}
    return {meta,total:validateStats(total),day:validateStats(daily),dayId:day};
  }
  private policy():BillingPolicy|null {try{return billingPolicy(this.env);}catch{return null;}}
  async viewIn(tx:DurableObjectTransaction,now:number){const s=await this.state(tx,now);return {quota:snapshot(now,this.offsetMinutes,s.day,s.total,this.policy(),s.meta.legacyReviewRequired),daily:s.day,total:s.total};}
  async view(){return this.storage.transaction(tx=>this.viewIn(tx,Date.now()));}
  async reserve(tx:DurableObjectTransaction,now:number):Promise<string>{
    const s=await this.state(tx,now);if(s.meta.legacyReviewRequired)throw new ApiError('BILLING_REVIEW_REQUIRED',503);
    const p=billingPolicy(this.env);if(s.day.successes+s.day.reservedTasks>=DAILY_LIMIT)throw new ApiError('QUOTA_EXHAUSTED',429);
    if(!hasBudget(s.day,s.total,p))throw new ApiError('BUDGET_EXHAUSTED',429);
    for(const stats of [s.day,s.total])apply(stats,s=>s.reservedTasks++);
    await tx.put({[dayKey(s.dayId)]:s.day,[TOTAL]:s.total});return s.dayId;
  }
  async begin(id:string,index:number):Promise<void>{
    await this.storage.transaction(async tx=>{
      const now=Date.now(),job=await tx.get<Job>(jobKey(id));if(!job?.billing||job.billing.settled||await tx.get<string>('active')!==id||index<0||index>1||await tx.get(attemptKey(id,index)))throw unavailable();
      if(index===1&&!await tx.get(attemptKey(id,0)))throw unavailable();
      const s=await this.state(tx,now,job.billing.day),p=billingPolicy(this.env);
      // A retry crossing midnight consumes budget of its original business-task day.
      if(!hasBudget(s.day,s.total,p))throw new ApiError('BUDGET_EXHAUSTED',429);
      const ceiling=attemptCeiling(p);
      for(const stats of [s.day,s.total])apply(stats,s=>{s.httpRequests++;s.retries+=index;s.unknownUsageAttempts++;s.reservedMicros=add(s.reservedMicros,ceiling);});
      const record:Attempt={index,startedAt:now,finishedAt:null,state:'STARTED',httpStatus:null,errorCode:null,model:'deepseek-flash',currency:'CNY',tokens:null,estimatedMicros:null,ceilingMicros:ceiling,rateCard:{id:p.id,inputMicrosPerMillion:p.inputMicrosPerMillion,cachedInputMicrosPerMillion:p.cachedInputMicrosPerMillion,outputMicrosPerMillion:p.outputMicrosPerMillion}};
      await tx.put({[attemptKey(id,index)]:record,[dayKey(s.dayId)]:s.day,[TOTAL]:s.total});
    });
  }
  async finish(id:string,index:number,outcome:{httpStatus:number|null;errorCode:string|null;tokens:TokenReport|null}):Promise<void>{
    await this.storage.transaction(async tx=>{
      const now=Date.now(),job=await tx.get<Job>(jobKey(id)),attempt=await tx.get<Attempt>(attemptKey(id,index));
      if(!job?.billing||!attempt||attempt.state!=='STARTED')throw unavailable();
      const s=await this.state(tx,now,job.billing.day);let cost:string|null=null;
      if(outcome.tokens){cost=estimateCost(outcome.tokens,attempt.rateCard);if(BigInt(cost)>BigInt(attempt.ceilingMicros))throw unavailable();
        for(const stats of [s.day,s.total])apply(stats,s=>{s.unknownUsageAttempts--;s.knownPromptTokens+=outcome.tokens!.promptTokens;s.knownCompletionTokens+=outcome.tokens!.completionTokens;s.reservedMicros=add(s.reservedMicros,'-'+attempt.ceilingMicros);s.committedMicros=add(s.committedMicros,cost!);});
      }
      await tx.put({[attemptKey(id,index)]:{...attempt,...outcome,finishedAt:now,state:outcome.tokens?'KNOWN':'UNKNOWN',estimatedMicros:cost},[dayKey(s.dayId)]:s.day,[TOTAL]:s.total});
    });
  }
  async settle(tx:DurableObjectTransaction,id:string,saved:SavedResponse,now:number):Promise<QuotaSnapshot>{
    const job=await tx.get<Job>(jobKey(id));if(!job?.billing||job.billing.settled||job.response)throw unavailable();
    const s=await this.state(tx,now,job.billing.day),ok=saved.status===200&&plain(saved.body)&&saved.body.status==='success';
    // A deadline or interrupted transport can finish the business task before its I/O callback.
    // Preserve the full cost hold and mark that attempt uncertain; late callbacks cannot overwrite it.
    for(let index=0;index<2;index++){const attempt=await tx.get<Attempt>(attemptKey(id,index));if(attempt?.state==='STARTED'){
      const error=plain(saved.body)&&plain(saved.body.error)&&typeof saved.body.error.code==='string'?saved.body.error.code:'OUTCOME_UNKNOWN';
      await tx.put(attemptKey(id,index),{...attempt,state:'UNKNOWN',finishedAt:now,errorCode:error});
    }}
    for(const stats of [s.day,s.total])apply(stats,s=>{s.reservedTasks--;if(ok)s.successes++;else s.failedTasks++;});
    await tx.put({[dayKey(s.dayId)]:s.day,[TOTAL]:s.total,[jobKey(id)]:{...job,response:saved,billing:{...job.billing,settled:true}}});await tx.delete('active');
    return (await this.viewIn(tx,now)).quota;
  }
}

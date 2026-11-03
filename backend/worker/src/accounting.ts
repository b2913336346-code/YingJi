import type { Bindings } from './env';
import { ApiError, plain } from './protocol';

export const DAILY_LIMIT = 30;
export const INPUT_TOKEN_CAP = 32768;
export const OUTPUT_TOKEN_CAP = 512;
export interface TokenReport { promptTokens:number; completionTokens:number; totalTokens:number; cacheHitTokens:number|null; cacheMissTokens:number|null }
export interface RateCard { id:string; inputMicrosPerMillion:string; cachedInputMicrosPerMillion:string; outputMicrosPerMillion:string }
export interface BillingPolicy extends RateCard { currency:'CNY'; verification:'MOCK'|'CONFIRMED'; dailyBudgetMicros:string; totalBudgetMicros:string }
export interface Stats { successes:number; reservedTasks:number; failedTasks:number; httpRequests:number; retries:number; knownPromptTokens:number; knownCompletionTokens:number; unknownUsageAttempts:number; committedMicros:string; reservedMicros:string }
export interface QuotaSnapshot { status:'AVAILABLE'|'EXHAUSTED'|'BUDGET_BLOCKED'|'BILLING_UNCONFIGURED'|'REVIEW_REQUIRED'; day:string; timeZone:string; limit:number; used:number; reserved:number; remaining:number; resetsAt:string; budget:{currency:'CNY'; status:'CONFIGURED'|'UNCONFIGURED'|'REVIEW_REQUIRED'; estimatedMicros:string; reservedMicros:string; dailyLimitMicros:string|null; totalEstimatedMicros:string; totalReservedMicros:string; totalLimitMicros:string|null; actualCostVerified:false} }
export const zeroStats=():Stats=>({successes:0,reservedTasks:0,failedTasks:0,httpRequests:0,retries:0,knownPromptTokens:0,knownCompletionTokens:0,unknownUsageAttempts:0,committedMicros:'0',reservedMicros:'0'});
const invalid=()=>new ApiError('BILLING_UNCONFIGURED',503);
function money(value:unknown):string { if(typeof value!=='string'||!/^([1-9][0-9]{0,14})$/.test(value))throw invalid();return value; }
export function billingPolicy(env:Bindings):BillingPolicy {
  try {
    const p:unknown=JSON.parse(env.BILLING_POLICY??'');
    if(!plain(p)||Object.keys(p).sort().join(',')!==['id','currency','verification','inputMicrosPerMillion','cachedInputMicrosPerMillion','outputMicrosPerMillion','dailyBudgetMicros','totalBudgetMicros'].sort().join(',')||
      typeof p.id!=='string'||!/^[A-Za-z0-9_-]{1,60}$/.test(p.id)||p.currency!=='CNY'||!['MOCK','CONFIRMED'].includes(String(p.verification)))throw invalid();
    if(p.verification==='MOCK'&&env.UPSTREAM_MODE!=='MOCK')throw invalid();
    const result={...p,inputMicrosPerMillion:money(p.inputMicrosPerMillion),cachedInputMicrosPerMillion:money(p.cachedInputMicrosPerMillion),outputMicrosPerMillion:money(p.outputMicrosPerMillion),dailyBudgetMicros:money(p.dailyBudgetMicros),totalBudgetMicros:money(p.totalBudgetMicros)} as BillingPolicy;
    if(BigInt(result.cachedInputMicrosPerMillion)>BigInt(result.inputMicrosPerMillion))throw invalid();return result;
  } catch {throw invalid();}
}
export function serverDay(now:number,offsetMinutes:number):{day:string;resetsAt:string;timeZone:string} {
  const shifted=now+offsetMinutes*60000;const day=new Date(shifted).toISOString().slice(0,10);
  return {day,resetsAt:new Date(Date.parse(day+'T00:00:00.000Z')+86400000-offsetMinutes*60000).toISOString(),timeZone:offsetMinutes===480?'Asia/Shanghai':'UTC'};
}
export function tokenReport(value:unknown):TokenReport|null {
  if(!plain(value)||![value.prompt_tokens,value.completion_tokens,value.total_tokens].every(v=>typeof v==='number'&&Number.isSafeInteger(v)&&v>=0)||Number(value.prompt_tokens)>INPUT_TOKEN_CAP||Number(value.completion_tokens)>OUTPUT_TOKEN_CAP||Number(value.total_tokens)!==Number(value.prompt_tokens)+Number(value.completion_tokens))return null;
  const hit=value.prompt_cache_hit_tokens,miss=value.prompt_cache_miss_tokens;
  if(hit!==undefined||miss!==undefined){if(![hit,miss].every(v=>typeof v==='number'&&Number.isSafeInteger(v)&&v>=0)||Number(hit)+Number(miss)!==Number(value.prompt_tokens))return null;}
  return {promptTokens:Number(value.prompt_tokens),completionTokens:Number(value.completion_tokens),totalTokens:Number(value.total_tokens),cacheHitTokens:hit===undefined?null:Number(hit),cacheMissTokens:miss===undefined?null:Number(miss)};
}
export function estimateCost(tokens:TokenReport,rates:RateCard):string {
  const hit=tokens.cacheHitTokens??0,miss=tokens.cacheMissTokens??tokens.promptTokens;
  return ((BigInt(hit)*BigInt(rates.cachedInputMicrosPerMillion)+BigInt(miss)*BigInt(rates.inputMicrosPerMillion)+BigInt(tokens.completionTokens)*BigInt(rates.outputMicrosPerMillion)+999999n)/1000000n).toString();
}
export const attemptCeiling=(p:RateCard)=>estimateCost({promptTokens:INPUT_TOKEN_CAP,completionTokens:OUTPUT_TOKEN_CAP,totalTokens:INPUT_TOKEN_CAP+OUTPUT_TOKEN_CAP,cacheHitTokens:0,cacheMissTokens:INPUT_TOKEN_CAP},p);
export const hasBudget=(day:Stats,total:Stats,p:BillingPolicy)=>BigInt(day.committedMicros)+BigInt(day.reservedMicros)+BigInt(attemptCeiling(p))<=BigInt(p.dailyBudgetMicros)&&BigInt(total.committedMicros)+BigInt(total.reservedMicros)+BigInt(attemptCeiling(p))<=BigInt(p.totalBudgetMicros);
export function validateStats(s:Stats):Stats {
  if(!plain(s)||['successes','reservedTasks','failedTasks','httpRequests','retries','knownPromptTokens','knownCompletionTokens','unknownUsageAttempts'].some(k=>typeof s[k]!=='number'||!Number.isSafeInteger(s[k])||Number(s[k])<0)||!/^\d+$/.test(s.committedMicros)||!/^\d+$/.test(s.reservedMicros))throw new ApiError('STORAGE_UNAVAILABLE',503);
  return s;
}
export function snapshot(now:number,offset:number,day:Stats,total:Stats,policy:BillingPolicy|null,review:boolean):QuotaSnapshot {
  const remaining=Math.max(0,DAILY_LIMIT-day.successes-day.reservedTasks);
  const status=review?'REVIEW_REQUIRED':!policy?'BILLING_UNCONFIGURED':remaining===0?'EXHAUSTED':!hasBudget(day,total,policy)?'BUDGET_BLOCKED':'AVAILABLE';
  return {status,...serverDay(now,offset),limit:DAILY_LIMIT,used:day.successes,reserved:day.reservedTasks,remaining,budget:{currency:'CNY',status:review?'REVIEW_REQUIRED':policy?'CONFIGURED':'UNCONFIGURED',estimatedMicros:day.committedMicros,reservedMicros:day.reservedMicros,dailyLimitMicros:policy?.dailyBudgetMicros??null,totalEstimatedMicros:total.committedMicros,totalReservedMicros:total.reservedMicros,totalLimitMicros:policy?.totalBudgetMicros??null,actualCostVerified:false}};
}

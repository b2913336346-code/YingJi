import { expect, test } from 'vitest';
import { attemptCeiling, billingPolicy, estimateCost, hasBudget, serverDay, snapshot, tokenReport, zeroStats } from '../src/accounting';
import type { Bindings } from '../src/env';
import { API_KEY } from './fixtures';
const policy={id:'TEST_ONLY_V1',currency:'CNY',verification:'MOCK',inputMicrosPerMillion:'1000000',cachedInputMicrosPerMillion:'100000',outputMicrosPerMillion:'2000000',dailyBudgetMicros:'100000000',totalBudgetMicros:'1000000000'};
const env=(p:unknown=policy)=>({UPSTREAM_MODE:'MOCK',BILLING_POLICY:JSON.stringify(p)}) as Bindings;
test('server day uses exact Shanghai midnight and leap-day boundaries',()=>{
  expect(serverDay(Date.parse('2028-02-29T15:59:59.999Z'),480)).toEqual({day:'2028-02-29',resetsAt:'2028-02-29T16:00:00.000Z',timeZone:'Asia/Shanghai'});
  expect(serverDay(Date.parse('2028-02-29T16:00:00.000Z'),480)).toEqual({day:'2028-03-01',resetsAt:'2028-03-01T16:00:00.000Z',timeZone:'Asia/Shanghai'});
});
test('money uses integer micro-CNY and ceiling rounding without floating-point loss',()=>{
  const p=billingPolicy(env());expect(attemptCeiling(p)).toBe('33792');
  expect(estimateCost({promptTokens:100,completionTokens:40,totalTokens:140,cacheHitTokens:80,cacheMissTokens:20},p)).toBe('108');
  expect(estimateCost({promptTokens:100,completionTokens:40,totalTokens:140,cacheHitTokens:null,cacheMissTokens:null},p)).toBe('180');
  expect(estimateCost({promptTokens:1,completionTokens:0,totalTokens:1,cacheHitTokens:null,cacheMissTokens:null},{...p,inputMicrosPerMillion:'1'})).toBe('1');
});
test('unset unverified negative fractional mixed-currency or unsafe billing fails closed',()=>{
  for(const p of [null,{}, {...policy,verification:'UNVERIFIED'},{...policy,dailyBudgetMicros:'0'},{...policy,currency:'USD'},{...policy,inputMicrosPerMillion:1.2},{...policy,outputMicrosPerMillion:'-1'},{...policy,totalBudgetMicros:'1000000000000000'},{...policy,cachedInputMicrosPerMillion:'2000000'},{...policy,extra:'unknown'}])expect(()=>billingPolicy(env(p))).toThrowError(expect.objectContaining({code:'BILLING_UNCONFIGURED'}));
  expect(()=>billingPolicy({...env(),UPSTREAM_MODE:'LIVE'})).toThrow();
});
test('token cache splits must balance and missing or inconsistent usage stays unknown',()=>{
  expect(tokenReport({prompt_tokens:100,completion_tokens:40,total_tokens:140,prompt_cache_hit_tokens:80,prompt_cache_miss_tokens:20})).toMatchObject({cacheHitTokens:80,cacheMissTokens:20});
  for(const p of [{},{prompt_tokens:100,completion_tokens:40,total_tokens:999},{prompt_tokens:100,completion_tokens:40,total_tokens:140,prompt_cache_hit_tokens:80},{prompt_tokens:32769,completion_tokens:0,total_tokens:32769}])expect(tokenReport(p)).toBeNull();
});
test('both daily and lifetime caps include all unresolved reservations',()=>{
  const p=billingPolicy(env({...policy,dailyBudgetMicros:'33792',totalBudgetMicros:'33792'})),day=zeroStats(),total=zeroStats();expect(hasBudget(day,total,p)).toBe(true);
  total.reservedMicros='1';expect(hasBudget(day,total,p)).toBe(false);
  const q=snapshot(Date.parse('2026-10-19T00:00:00Z'),480,day,total,p,false);expect(q.status).toBe('BUDGET_BLOCKED');expect(q.budget.actualCostVerified).toBe(false);
});

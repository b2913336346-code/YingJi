import { expect, test, vi, afterEach } from 'vitest';
import { ApiError, parseRequest, readRequest, validateHints } from '../src/protocol';
import { input, hints, blankConditions } from './fixtures';
afterEach(()=>vi.useRealTimers());

test('query is optional only for shortcut modes and length is capped in Unicode characters',()=>{
  expect(()=>input({mode:'QUERY',userQuery:''})).toThrow(ApiError);
  expect(input({mode:'QUERY',userQuery:'影'.repeat(500)}).userQuery.length).toBe(500);
  expect(()=>input({mode:'QUERY',userQuery:'影'.repeat(501)})).toThrow(ApiError);
  expect(()=>input({mode:'TONIGHT',userQuery:'hidden raw text'})).toThrow(ApiError);
});
test('unknown request and summary fields cannot carry a library or raw notes',()=>{
  const base=input();
  for(const bad of [{...base,movies:[{name:'private title'}]}, {...base,profileSummary:{...base.profileSummary,notes:'raw notes'}}])expect(()=>parseRequest(bad)).toThrow(ApiError);
});
test('enums versions identities and UUIDs reject type coercion and Room primary keys',()=>{
  const base=input();const bad=[{...base,mode:['FOR_YOU']},{...base,profileVersion:-1},{...base,sourceSettingsVersion:1.2},{...base,requestId:'7'},
    {...base,excludeExternalIds:[7]},{...base,excludeExternalIds:[{sourceId:'catalogue',externalId:' id '}]},
    {...base,profileSummary:{...base.profileSummary,explicit:[{topic:'科幻',direction:['LIKE']}]}}];
  for(const row of bad)expect(()=>parseRequest(row)).toThrow(ApiError);
});
test('external consent is independent of local source defaults',()=>{
  expect(()=>parseRequest({...input(),externalProcessingConsent:false})).toThrowError(expect.objectContaining({code:'CONSENT_REQUIRED'}));
  expect(input().profileSummary.enabledSources).toEqual({ratings:false,behavior:false,feedback:true});
});
test('source switch matrix excludes disabled rating and feedback inference and all behavior votes',()=>{
  for(const ratings of [false,true])for(const feedback of [false,true])for(const source of ['RATING','FEEDBACK'] as const){
    const base=input();const raw={...base,profileSummary:{enabledSources:{ratings,behavior:true,feedback},explicit:[],inferred:[{topic:'科幻',direction:'LIKE',source}]}};
    if(source==='RATING'?ratings:feedback)expect(parseRequest(raw).profileSummary.inferred).toHaveLength(1);else expect(()=>parseRequest(raw)).toThrow(ApiError);
  }
  const base=input();expect(()=>parseRequest({...base,profileSummary:{...base.profileSummary,inferred:[{topic:'科幻',direction:'LIKE',source:'BEHAVIOR'}]}})).toThrow(ApiError);
});
test('duplicate preference aliases conflicting dates and overlong exclusions fail closed',()=>{
  const base=input();
  for(const raw of [{...base,excludeExternalIds:Array.from({length:51},()=>({sourceId:'catalogue',externalId:'a'}))},
    {...base,hardConditions:{...blankConditions(),minYear:2021,maxYear:2020}},
    {...base,profileSummary:{...base.profileSummary,explicit:[{topic:'科幻',direction:'LIKE'},{topic:' 科幻 ',direction:'DISLIKE'}]}}])expect(()=>parseRequest(raw)).toThrow(ApiError);
});
test('explicit exclusions conflicting with required topics fail before spending a model call',()=>{
  const base=input();expect(()=>parseRequest({...base,hardConditions:{...blankConditions(),requiredTopics:['科幻']},
    profileSummary:{...base.profileSummary,explicit:[{topic:'科幻',direction:'DISLIKE'}]}})).toThrowError(expect.objectContaining({code:'CONFLICTING_CONDITIONS'}));
});
test('unknown model fields and fabricated film facts are not accepted as hints',()=>{
  for(const raw of [{...hints(),candidates:[{title:'fiction'}]}, {preferredTopics:['科幻'],conditions:{...blankConditions(),poster:'https://invalid'}}])expect(()=>validateHints(raw,input())).toThrowError(expect.objectContaining({code:'MODEL_FORMAT'}));
});
test('model hints cannot loosen local hard conditions or explicit exclusions',()=>{
  const base=input();base.hardConditions={requiredTopics:['电影'],excludedTopics:['恐怖'],minYear:2020,maxYear:2025,maxRuntimeMinutes:90};
  base.profileSummary.explicit=[{topic:'科幻',direction:'DISLIKE'}];
  const result=validateHints({preferredTopics:['科幻','喜剧'],conditions:{requiredTopics:[],excludedTopics:[],minYear:1980,maxYear:2030,maxRuntimeMinutes:300}},base);
  expect(result).toEqual({preferredTopics:['喜剧'],conditions:{requiredTopics:['电影'],excludedTopics:['恐怖','科幻'],minYear:2020,maxYear:2025,maxRuntimeMinutes:90}});
  expect(()=>validateHints({preferredTopics:[],conditions:{...blankConditions(),requiredTopics:['科幻']}},base)).toThrow(ApiError);
});
test('slow client body is cancelled at the input deadline without a fixed delay',async()=>{
  vi.useFakeTimers();const cancel=vi.fn();const stream=new ReadableStream<Uint8Array>({cancel});
  const request=new Request('https://test',{method:'POST',headers:{'Content-Type':'application/json'},body:stream});
  const done=expect(readRequest(request)).rejects.toMatchObject({code:'REQUEST_TIMEOUT'});
  await vi.advanceTimersByTimeAsync(3001);await done;expect(cancel).toHaveBeenCalledTimes(1);
});

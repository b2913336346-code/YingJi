import { beforeAll,afterAll,expect,test } from 'vitest';
import { setupNetwork } from '@msw/cloudflare';
import { http } from 'msw';
const network=setupNetwork();
beforeAll(()=>{network.enable();network.use(http.all('*',()=>Response.json({mock:true})));});afterAll(()=>network.disable());
test('Workers transport uses its supported manual redirect mode',async()=>{
  const result=await fetch('https://unit-only.test/',{method:'POST',redirect:'manual',body:'{}'});
  expect(await result.json()).toEqual({mock:true});
});

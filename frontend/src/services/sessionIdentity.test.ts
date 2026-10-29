import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { AxiosResponse, InternalAxiosRequestConfig } from "axios";

type Pending={config:InternalAxiosRequestConfig;resolve:(response:AxiosResponse)=>void;reject:(error:unknown)=>void};
let pending:Pending[],service:typeof import("./api"),axios:typeof import("axios").default;
beforeEach(async()=>{
  vi.resetModules();pending=[];axios=(await import("axios")).default;
  axios.defaults.adapter=config=>new Promise<AxiosResponse>((resolve,reject)=>pending.push({config,resolve,reject}));
  service=await import("./api");
});
afterEach(()=>{service.setAuthToken(undefined);service.advanceAdminSession();vi.restoreAllMocks();});
const dispatched=async()=>{await Promise.resolve();await Promise.resolve();};
function respond(request:Pending,status:number,data:unknown) {
  const response={config:request.config,status,statusText:String(status),headers:{},data};
  if(status>=400) request.reject(new axios.AxiosError("fixture",undefined,request.config,undefined,response));else request.resolve(response);
}
it("ignores the previous player's late 401 and stale success after switching identity",async()=>{
  const expired=vi.fn();const unsubscribe=service.subscribeAuthExpired(expired);
  service.setAuthToken("A");const old=service.authApi.me().catch(error=>error);await dispatched();
  service.setAuthToken("B");respond(pending[0],401,{});await old;expect(expired).not.toHaveBeenCalled();
  const current=service.authApi.me();await dispatched();expect(pending[1].config.headers["X-Auth-Token"]).toBe("B");respond(pending[1],200,{id:"B"});expect((await current).id).toBe("B");unsubscribe();
});
it("does not let a late admin 401 or operation-proof challenge affect the next session",async()=>{
  const expired=vi.fn(),prompt=vi.spyOn(window,"prompt");const unsubscribe=service.subscribeAdminAuthExpired(expired);
  const old=service.adminLabApi.control("experiment","start").catch(error=>error);await dispatched();
  const challenged=service.adminLabApi.control("experiment","pause").catch(error=>error);await dispatched();
  service.advanceAdminSession();respond(pending[0],401,{});respond(pending[1],428,{code:"ADMIN_OPERATION_PROOF_REQUIRED",challengeId:"old"});
  expect(axios.isCancel(await old)).toBe(true);expect(axios.isCancel(await challenged)).toBe(true);
  expect(expired).not.toHaveBeenCalled();expect(prompt).not.toHaveBeenCalled();expect(pending).toHaveLength(2);unsubscribe();
});
it.each([403,503])("keeps both identities after a current request returns %s",async status=>{
  const adminExpired=vi.fn(),userExpired=vi.fn();const unsubscribeAdmin=service.subscribeAdminAuthExpired(adminExpired),unsubscribeUser=service.subscribeAuthExpired(userExpired);
  const admin=service.adminApi.me().catch(error=>error);const user=service.authApi.me().catch(error=>error);await dispatched();
  for(const request of pending) respond(request,status,{});await Promise.all([admin,user]);
  expect(adminExpired).not.toHaveBeenCalled();expect(userExpired).not.toHaveBeenCalled();unsubscribeAdmin();unsubscribeUser();
});

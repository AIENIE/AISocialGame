import type { CatalogModel, ThinkingMode, ThinkingEffort } from "@/types/aiModelConfig";
import type { LabSelection } from "@/types/lab";

export function ModelSelectionFields({models,value,change,prefix}:{models:CatalogModel[];value:LabSelection;change:(value:LabSelection)=>void;prefix:string}) {
  const model=models.find(option=>option.modelKey===value.modelKey);
  const thinking=model?.thinking;
  const modes:ThinkingMode[]=["DEFAULT",...(thinking?.declared&&thinking.supportsEnable?["ENABLED" as const]:[]),...(thinking?.declared&&thinking.supportsDisable?["DISABLED" as const]:[])];
  const labels:Record<ThinkingMode,string>={DEFAULT:"跟随服务默认",ENABLED:"开启思考",DISABLED:"关闭思考"};
  return <div className="lab-form-grid">
    <label className="lab-field" htmlFor={prefix+"-model"}>模型<select id={prefix+"-model"} value={value.modelKey} required onChange={event=>change({modelKey:event.target.value,thinkingMode:"DEFAULT",thinkingEffort:null})}>
      <option value="">选择模型</option>{value.modelKey&&!model&&<option value={value.modelKey}>{value.modelKey} · 待调整</option>}{models.map(option=><option key={option.modelKey} value={option.modelKey}>{option.displayName}</option>)}</select></label>
    <label className="lab-field" htmlFor={prefix+"-thinking"}>思考模式<select id={prefix+"-thinking"} value={value.thinkingMode} onChange={event=>change({...value,thinkingMode:event.target.value as ThinkingMode,thinkingEffort:null})}>
      {!modes.includes(value.thinkingMode)&&<option value={value.thinkingMode}>{labels[value.thinkingMode]} · 待调整</option>}{modes.map(mode=><option key={mode} value={mode}>{labels[mode]}</option>)}</select></label>
    {value.thinkingMode==="ENABLED"&&<label className="lab-field" htmlFor={prefix+"-effort"}>思考强度<select id={prefix+"-effort"} value={value.thinkingEffort ?? ""} onChange={event=>change({...value,thinkingEffort:event.target.value as ThinkingEffort || null})}>
      <option value="">跟随模型默认</option>{value.thinkingEffort&&!thinking?.options.some(option=>option.effort===value.thinkingEffort)&&<option value={value.thinkingEffort}>{value.thinkingEffort} · 待调整</option>}{thinking?.declared&&thinking.options.map(option=><option key={option.effort} value={option.effort}>{option.label}</option>)}</select></label>}
    {!thinking?.declared&&<p className="lab-small self-end">思考能力未知，仅使用服务默认。</p>}
  </div>;
}

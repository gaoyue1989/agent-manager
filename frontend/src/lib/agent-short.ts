// 业务 short 名白名单（防注入；与 backend k8s.ShortName 派生同形态）：
// k8sName = "oaf-" + slug，short 即去掉前缀后的小写字母/数字/连字符序列。
// 注意不能写成 /^[a-z0-9](-[a-z0-9])*$/——那只匹配单字符连段（a / a-b），
// 正常名（abc、cs-ticket-stress）会被判非法，/agent/{short}/* 反代直接 404。
const SHORT_NAME_RE = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?$/;

// 长度上限与 K8s DNS label 一致（backend 派生时已按 63 截断，此处防御非法输入）。
const MAX_SHORT_NAME_LENGTH = 63;

export function isValidAgentShortName(short: string): boolean {
  return short.length > 0
    && short.length <= MAX_SHORT_NAME_LENGTH
    && SHORT_NAME_RE.test(short);
}

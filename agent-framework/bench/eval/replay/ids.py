"""客户端可控标识符校验（issue #97 问题 2/9/10 同链路共治）。

evalpack 链路里所有「客户端可控标识符」——studio 上传包内 manifest 的 pack_id、
from-collector 请求体的 pack_id、collector x-eval-session 头派生的 sid、包内
cases-draft 的 case_id——拼进文件路径前必须经本模块校验，杜绝路径穿越
（`../../escaped`、绝对路径、反斜杠）与任意目录删除/写入。

合法形态（存量 id 全集已逐一核对通过）：
  pack:  pk-e2e-demo / pk-gold / pk-demo-round-20261004-122540
  sid:   sess-rec-1 / gold-rec-1 / auto-0001-llm-3f1d2e4a（指纹聚类合成形态）
  case:  case_replay_{sid[:24]} / case_chat_basic_001（用例库 9 条）
"""

import re

# 首字符字母/数字，其余仅字母/数字/点/下划线/连字符，总长 1~128。
# 注意：正则本身已排除 '/' 与反斜杠（字符类未收录），'..' 需显式拒绝（点合法单出现）；
# 结尾必须用 \Z 而非 $——Python re 的 $ 匹配串尾换行之前，'a\n' 会漏过白名单（评审发现）
_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\Z")


class InvalidIdentifierError(ValueError):
    """标识符不合法（含原值与用途，studio 侧映射 400）。"""


def validate_id(kind: str, value: object) -> str:
    """校验标识符并原样返回；不合法抛 InvalidIdentifierError（ValueError 子类）。

    kind：标识符用途（如 "pack_id" / "session id" / "case_id"），仅用于错误信息。
    显式拒绝 '..'、'/'、反斜杠——正则已覆盖后两者，显式检查只为给出更明确的错误提示。
    """
    if not isinstance(value, str) or not value:
        raise InvalidIdentifierError(f"{kind} 不合法：必须是非空字符串")
    if not _ID_RE.match(value) or ".." in value:
        raise InvalidIdentifierError(
            f"{kind} 不合法：{value[:64]!r}（仅允许字母/数字/点/下划线/连字符，"
            f"字母或数字开头，长度 1~128，禁止连续点 '..'）")
    if "/" in value or "\\" in value:
        # 正则字符类已排除两者，此分支理论上不可达，保留以给出最明确的错误指向
        raise InvalidIdentifierError(f"{kind} 不合法：禁止包含路径分隔符：{value[:64]!r}")
    return value

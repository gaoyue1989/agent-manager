# strict 分支保护下的合并轮转手册

2026-11-11 批量合并 9 个 PR + 2026-11-22 收口 #389 实测沉淀。

## 轮转节奏

- strict 模式：任何 master 合并都使其余 open PR 变 `BEHIND`，无法绕过，只能逐轮「`gh pr update-branch` → 等 CI → 合并」。
- 一轮 CI 耗时：agent-framework PR（mvn + E2E 矩阵）约 6-7 分钟（2026-11-24 起沙箱生命周期 L 组拆两个 job 并行，此前约 12 分钟）；前端/后端 PR 约 5-10 分钟；`E2E 平台（kind + 控制台全功能）` 约 6-7 分钟（2026-11-24 起五个分片并行，此前 20-40 分钟）。修一个 PR 的等待窗口足够做下一个 PR 的 CR。

## 状态语义

- `gh pr checks` 里 `skipping` = 目录过滤设计内，**视为通过**（AGENTS.md：PR 必须照常触发工作流，否则必需检查 pending 卡死）。
- `mergeStateStatus=UNSTABLE` 且仅剩非必需 job（L 组观察期等）pending 是暂态，等它收尾即 `CLEAN`。
- `BLOCKED` = 必需检查未全绿，别急着合。

## 并行会话干扰（真实踩坑）

- 并行会话/人工会中途往 master 落 PR，改的可能是你正要解冲突的文件：**每次 merge 前必须复核祖先关系**：
  ```bash
  git fetch origin master
  git merge-base --is-ancestor origin/master <head> || gh pr update-branch <N>
  ```
  别只信几分钟前的 CLEAN。

## 冲突处理（update-branch 失败时）

- `gh pr update-branch` 对有冲突的 PR 失败：本地 merge master 进 PR 分支解冲突后 push 到 PR head 分支。
- 同文件 EOF 追加测试块（多 PR 共用一个测试文件）三方合并易冲突：
  - 「两侧全保留」会丢方法收尾 `}`——用括号深度扫描自检：`awk '{for(i=1;i<=length($0);i++){c=substr($0,i,1);if(c=="{")d++;if(c=="}")d--}} END{print d}'`，应为 0。
  - **必须在推送前的那棵精确树上跑编译/测试**，不要拿「之后才 amend 过的树」的验证结果背书（曾致 CI 编译红一次）。

## 权限

- push token 有 repo 权限，PR 作者与当前账号相同，修复 commit 直接推 PR head 分支即可。
- master 禁止直接 push（enforce_admins），一切变更走 PR。

type Props = {
  owner: 'AGENT' | 'HUMAN';
  reason: string | null;
  sandboxReady: boolean;
  sandboxId: string | null;
  busy: boolean;
  onTakeover: () => Promise<void>;
  onRelease: () => Promise<void>;
};

export function ControlBar({ owner, reason, sandboxReady, sandboxId, busy, onTakeover, onRelease }: Props) {
  const human = owner === 'HUMAN';
  return (
    <div className="panel-head">
      <span className={`badge ${human ? 'human' : 'agent'}`}>{human ? '人工接管中' : 'Agent 控制中'}</span>
      <span className="control-note">
        {human ? '画面可操作' : '画面只读'}
        {reason ? ` · ${reason}` : ''}
        {sandboxReady ? '' : ' · 沙箱未创建'}
        {sandboxId ? ` · ${sandboxId.slice(0, 8)}` : ''}
      </span>
      <div style={{ flex: 1 }} />
      <button className="btn warn" disabled={human || !sandboxReady} onClick={() => void onTakeover()}>
        接管
      </button>
      <button className="btn primary" disabled={!human || busy} onClick={() => void onRelease()}>
        交还控制权
      </button>
    </div>
  );
}

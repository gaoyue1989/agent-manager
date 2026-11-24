declare module '@novnc/novnc' {
  const RFB: new (target: HTMLElement, url: string, options?: Record<string, unknown>) => {
    viewOnly: boolean;
    scaleViewport: boolean;
    resizeSession: boolean;
    disconnect: () => void;
    addEventListener: (type: string, listener: (event: { detail?: { clean?: boolean } }) => void) => void;
  };
  export default RFB;
}

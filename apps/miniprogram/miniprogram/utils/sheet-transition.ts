type Patch = { mounted?: boolean; opened?: boolean }
type Timer = { set: (callback: () => void, delay: number) => ReturnType<typeof setTimeout>; clear: (id: ReturnType<typeof setTimeout>) => void }

/** 关闭过渡结束后才卸载；快速重开时取消上一次关闭。 */
export class SheetTransition {
  private pending?: ReturnType<typeof setTimeout>
  private visible = false
  private mounted = false
  private disposed = false
  constructor(private update: (patch: Patch) => void, private timer: Timer = { set: setTimeout, clear: clearTimeout }) {}
  setVisible(visible: boolean): void {
    if (this.disposed || visible === this.visible) return
    this.visible = visible
    this.cancel()
    if (visible) {
      if (this.mounted) { this.update({ opened: true }); return }
      this.mounted = true
      this.update({ mounted: true, opened: false })
      this.pending = this.timer.set(() => { this.pending = undefined; this.update({ opened: true }) }, 20)
    } else {
      this.update({ opened: false })
      this.pending = this.timer.set(() => { this.pending = undefined; this.mounted = false; this.update({ mounted: false }) }, 180)
    }
  }
  private cancel(): void { if (this.pending !== undefined) this.timer.clear(this.pending); this.pending = undefined }
  dispose(): void { this.disposed = true; this.cancel() }
}

/** 内容列居中时，仅在微信胶囊实际与内容列相交时留出空间。 */
export function menuRightSpace(windowWidth: number, menuLeft: number): number {
  const columnRight = windowWidth >= 500 ? (windowWidth + 460) / 2 : windowWidth
  return Math.max(12, columnRight - menuLeft + 12)
}

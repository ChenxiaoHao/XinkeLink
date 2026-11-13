/** 只约束展示容器，不改变原图或业务数据。 */
export function boundedImageRatio(value: unknown): number {
  const ratio = typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : 4 / 3
  return Math.max(0.8, Math.min(1.5, ratio))
}

export function imagePadding(width: unknown, height: unknown): number {
  const valid = typeof width === 'number' && typeof height === 'number'
    && Number.isFinite(width) && Number.isFinite(height) && width > 0 && height > 0
  return 100 / boundedImageRatio(valid ? width / height : undefined)
}

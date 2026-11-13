/** 只约束展示容器，不改变原图或业务数据。
 *  首页 feed 单图：高度不超过宽度的 0.75（4:3 封顶），过宽的下限 0.667（避免扁长条）。 */
export function boundedImageRatio(value: unknown): number {
  const ratio = typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : 4 / 3
  return Math.max(4 / 3, Math.min(1.5, ratio))
}

export function imagePadding(width: unknown, height: unknown): number {
  const valid = typeof width === 'number' && typeof height === 'number'
    && Number.isFinite(width) && Number.isFinite(height) && width > 0 && height > 0
  return 100 / boundedImageRatio(valid ? width / height : undefined)
}

/** 详情页单图完整显示：不夹比例，容器高度完全跟随原图（容器比例=图片比例时 aspectFill 无裁剪）。 */
export function naturalImagePadding(width: unknown, height: unknown): number {
  const valid = typeof width === 'number' && typeof height === 'number'
    && Number.isFinite(width) && Number.isFinite(height) && width > 0 && height > 0
  return valid ? height / width * 100 : 75
}

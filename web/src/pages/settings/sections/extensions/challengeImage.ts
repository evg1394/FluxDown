import { toDataURL } from 'qrcode'

// Model 2 即使使用最低纠错级别也最多容纳 7089 个数字；更大的不可信载荷无需交给编码器。
const MAX_QR_TEXT_LENGTH = 7089

/** 仅本地编码原文，不解析或请求其中的 URL；不可编码时让视图保留文本与复制入口。 */
export async function encodeQrChallengeImage(value: string): Promise<string | null> {
  if (value === '' || value.length > MAX_QR_TEXT_LENGTH) return null
  try {
    return await toDataURL(value, {
      errorCorrectionLevel: 'M',
      width: 240,
      margin: 4,
      color: { dark: '#000000ff', light: '#ffffffff' },
    })
  } catch {
    // 空间不足或当前浏览器不能绘制 canvas 时，原挑战仍由调用侧作为文本展示。
    return null
  }
}

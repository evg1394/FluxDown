import { describe, expect, test } from 'bun:test'
import { captureEntry } from './model'

describe('captureEntry', () => {
  test('省略与 URL 末段（含百分号解码后）相同的 out=', () => {
    expect(captureEntry('https://x.example/a.zip?s=1', 'a.zip').fileName).toBe('')
    expect(captureEntry('https://x.example/%E4%B8%AD.zip', '中.zip').fileName).toBe('')
    expect(captureEntry('https://x.example/a%20b.zip', 'a b.zip').fileName).toBe('')
  })

  test('真正改名或解码失败时保留 out=', () => {
    expect(captureEntry('https://x.example/%E4%B8%AD.zip', 'other.zip').fileName).toBe('other.zip')
    expect(captureEntry('https://x.example/%B6%D4.zip', '对.zip').fileName).toBe('对.zip')
    expect(captureEntry('https://x.example/%B6%D4.zip', '%B6%D4.zip').fileName).toBe('')
    expect(captureEntry('https://x.example/', 'index.html').fileName).toBe('index.html')
  })
})

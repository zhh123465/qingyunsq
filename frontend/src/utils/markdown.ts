import { marked } from 'marked';

export interface TocItem {
  id: string;
  text: string;
  level: number;
}

/**
 * 渲染 Markdown 并提取目录。
 *
 * 关键点：HTML 与目录来自**同一批 DOM 节点**，避免用正则在原始 markdown 上
 * 单独抓标题（会误把代码块里的 `#` 注释当标题，导致目录与真实锚点错位/乱码）。
 * 用 DOMParser 解析 marked 的输出，遍历真实 <h1-3> 注入 id 并收集目录文本。
 */
export function renderMarkdown(md: string): { html: string; toc: TocItem[] } {
  let html = '';
  try {
    html = marked.parse(md || '', { async: false }) as string;
  } catch {
    return { html: '', toc: [] };
  }
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const toc: TocItem[] = [];
  doc.querySelectorAll('h1, h2, h3').forEach((el, i) => {
    const id = `heading-${i}`;
    el.id = id;
    toc.push({
      id,
      text: (el.textContent || '').trim(),
      level: Number(el.tagName.charAt(1)),
    });
  });
  return { html: doc.body.innerHTML, toc };
}

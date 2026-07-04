import { marked } from 'marked';
import type { ResultEvent } from '../api/types';

export function ResultPanel({ result }: { result: ResultEvent }) {
  const html = marked.parse(result.output ?? '', { async: false }) as string;
  return (
    <section aria-label="运行结果">
      <header>
        <span className={result.success ? 'badge badge-ok' : 'badge badge-fail'}>
          {result.success ? '成功' : '失败'}
        </span>
        <span className="termination">结束原因：{result.termination}</span>
      </header>
      <article className="output" dangerouslySetInnerHTML={{ __html: html }} />
      <h3>证据</h3>
      {result.evidence.length === 0 ? (
        <p className="evidence-empty">无证据</p>
      ) : (
        <ul className="evidence">
          {result.evidence.map((a) => (
            <li key={a.id}>
              {a.meta?.uri && <a href={a.meta.uri} target="_blank" rel="noreferrer">{a.meta.uri}</a>}
              <p className="evidence-content">{a.content}</p>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

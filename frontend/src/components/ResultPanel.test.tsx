import { render, screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { ResultPanel } from './ResultPanel';
import type { ResultEvent } from '../api/types';

const base: ResultEvent = { success: true, output: '**加粗** 结论', evidence: [], termination: 'completed' };

test('渲染 Markdown 正文（加粗成 strong）', () => {
  const { container } = render(<ResultPanel result={base} />);
  expect(container.querySelector('strong')).toHaveTextContent('加粗');
});

test('成功徽章与 termination', () => {
  render(<ResultPanel result={base} />);
  expect(screen.getByText('成功')).toBeInTheDocument();
  expect(screen.getByText(/completed/)).toBeInTheDocument();
});

test('证据为空显示占位', () => {
  render(<ResultPanel result={base} />);
  expect(screen.getByText('无证据')).toBeInTheDocument();
});

test('证据带 meta.uri 渲染来源链接', () => {
  const withEv: ResultEvent = {
    ...base,
    evidence: [{ id: 'a', runId: 'r', kind: 'evidence', key: 'k', content: '片段', meta: { uri: 'http://x' } }],
  };
  render(<ResultPanel result={withEv} />);
  expect(screen.getByRole('link', { name: 'http://x' })).toHaveAttribute('href', 'http://x');
  expect(screen.getByText('片段')).toBeInTheDocument();
});

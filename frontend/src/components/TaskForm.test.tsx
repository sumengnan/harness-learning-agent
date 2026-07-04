import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { TaskForm } from './TaskForm';

test('填 query 后提交回传 type 与 query', async () => {
  const onSubmit = vi.fn();
  render(<TaskForm disabled={false} onSubmit={onSubmit} />);
  await userEvent.type(screen.getByLabelText('问题/主题'), '上下文工程');
  await userEvent.click(screen.getByRole('button', { name: '提交' }));
  expect(onSubmit).toHaveBeenCalledWith({ type: 'QA', query: '上下文工程' });
});

test('query 为空时提交按钮禁用', () => {
  render(<TaskForm disabled={false} onSubmit={() => {}} />);
  expect(screen.getByRole('button', { name: '提交' })).toBeDisabled();
});

test('disabled 时按钮禁用', async () => {
  render(<TaskForm disabled onSubmit={() => {}} />);
  await userEvent.type(screen.getByLabelText('问题/主题'), 'x');
  expect(screen.getByRole('button', { name: '提交' })).toBeDisabled();
});

test('提供 4 种任务类型', () => {
  render(<TaskForm disabled={false} onSubmit={() => {}} />);
  expect(screen.getAllByRole('option')).toHaveLength(4);
});

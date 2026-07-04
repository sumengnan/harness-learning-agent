import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, test } from 'vitest';
import App from './App';
import { MockEventSource, installMockEventSource } from './test/mockEventSource';

beforeEach(() => installMockEventSource());

test('提交→步骤→结果 全流程', async () => {
  render(<App />);
  await userEvent.type(screen.getByLabelText('问题/主题'), '上下文工程');
  await userEvent.click(screen.getByRole('button', { name: '提交' }));

  const es = MockEventSource.last();
  es.emit('step', { seq: 0, layer: 'L3', event: 'model_step', detail: '思考' });
  es.emit('result', { success: true, output: '综述完成', evidence: [], termination: 'completed' });

  expect(await screen.findByText('L3')).toBeInTheDocument();
  expect(await screen.findByLabelText('运行结果')).toBeInTheDocument();
  expect(screen.getByText(/综述完成/)).toBeInTheDocument();
});

test('fail → 错误横幅可重试', async () => {
  render(<App />);
  await userEvent.type(screen.getByLabelText('问题/主题'), 'x');
  await userEvent.click(screen.getByRole('button', { name: '提交' }));
  MockEventSource.last().emit('fail', { message: '出错了' });

  expect(await screen.findByText(/出错了/)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '重试' })).toBeInTheDocument();
});

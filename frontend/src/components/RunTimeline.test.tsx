import { render, screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { RunTimeline } from './RunTimeline';
import type { StepEvent } from '../api/types';

const steps: StepEvent[] = [
  { seq: 0, layer: 'L3', event: 'model_step', detail: '思考中' },
  { seq: 1, layer: 'L2', event: 'tool_invoke', detail: 'local_retrieve' },
];

test('渲染每一步及其层标签', () => {
  render(<RunTimeline steps={steps} active={false} />);
  expect(screen.getByText('L3')).toBeInTheDocument();
  expect(screen.getByText('L2')).toBeInTheDocument();
  expect(screen.getByText('model_step')).toBeInTheDocument();
  expect(screen.getByText('tool_invoke')).toBeInTheDocument();
});

test('active 时显示运行中指示', () => {
  render(<RunTimeline steps={steps} active />);
  expect(screen.getByText('运行中…')).toBeInTheDocument();
});

test('无步骤且非 active 不渲染运行中', () => {
  render(<RunTimeline steps={[]} active={false} />);
  expect(screen.queryByText('运行中…')).not.toBeInTheDocument();
});

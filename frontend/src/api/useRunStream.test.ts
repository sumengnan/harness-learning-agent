import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, test } from 'vitest';
import { useRunStream } from './useRunStream';
import { MockEventSource, installMockEventSource } from '../test/mockEventSource';

beforeEach(() => installMockEventSource());

describe('useRunStream', () => {
  test('step 累积 + result 收尾', () => {
    const { result } = renderHook(() => useRunStream());
    act(() => result.current.start({ type: 'SURVEY', query: 'ctx' }));
    expect(result.current.status).toBe('streaming');

    const es = MockEventSource.last();
    act(() => es.emit('step', { seq: 0, layer: 'L3', event: 'model_step', detail: 'a' }));
    act(() => es.emit('step', { seq: 1, layer: 'L2', event: 'tool_invoke', detail: 'b' }));
    expect(result.current.steps).toHaveLength(2);

    act(() => es.emit('result', { success: true, output: 'done', evidence: [], termination: 'completed' }));
    expect(result.current.status).toBe('done');
    expect(result.current.result?.output).toBe('done');
    expect(es.closed).toBe(true);
  });

  test('fail 事件 → error 态', () => {
    const { result } = renderHook(() => useRunStream());
    act(() => result.current.start({ type: 'QA', query: 'x' }));
    const es = MockEventSource.last();
    act(() => es.emit('fail', { message: 'boom' }));
    expect(result.current.status).toBe('error');
    expect(result.current.error).toContain('boom');
  });

  test('未收 result 前连接断开 → error 态', () => {
    const { result } = renderHook(() => useRunStream());
    act(() => result.current.start({ type: 'QA', query: 'x' }));
    const es = MockEventSource.last();
    act(() => es.emitError());
    expect(result.current.status).toBe('error');
  });

  test('result 后的关闭不算错误', () => {
    const { result } = renderHook(() => useRunStream());
    act(() => result.current.start({ type: 'QA', query: 'x' }));
    const es = MockEventSource.last();
    act(() => es.emit('result', { success: true, output: 'ok', evidence: [], termination: 'completed' }));
    act(() => es.emitError());               // 正常结束后底层可能再触发 onerror
    expect(result.current.status).toBe('done');
  });
});

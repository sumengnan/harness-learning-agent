import { useCallback, useReducer, useRef } from 'react';
import type { ResultEvent, RunStatus, StepEvent, TaskType } from './types';

interface State { status: RunStatus; steps: StepEvent[]; result: ResultEvent | null; error: string | null; }

type Action =
  | { type: 'START' }
  | { type: 'STEP'; step: StepEvent }
  | { type: 'RESULT'; result: ResultEvent }
  | { type: 'FAIL'; message: string }
  | { type: 'RESET' };

const initial: State = { status: 'idle', steps: [], result: null, error: null };

function reducer(state: State, action: Action): State {
  switch (action.type) {
    case 'START': return { status: 'streaming', steps: [], result: null, error: null };
    case 'STEP': return { ...state, steps: [...state.steps, action.step] };
    case 'RESULT': return { ...state, status: 'done', result: action.result };
    case 'FAIL':
      if (state.status === 'done') return state;         // 已完成，忽略迟到的错误
      return { ...state, status: 'error', error: action.message };
    case 'RESET': return initial;
    default: return state;
  }
}

export interface Params { type: TaskType; query: string; }

export function useRunStream() {
  const [state, dispatch] = useReducer(reducer, initial);
  const esRef = useRef<EventSource | null>(null);

  const start = useCallback((params: Params) => {
    esRef.current?.close();
    dispatch({ type: 'START' });
    const url = `/runs/stream?type=${encodeURIComponent(params.type)}&query=${encodeURIComponent(params.query)}`;
    const es = new EventSource(url);
    esRef.current = es;

    es.addEventListener('step', (e) => dispatch({ type: 'STEP', step: JSON.parse((e as MessageEvent).data) }));
    es.addEventListener('result', (e) => {
      dispatch({ type: 'RESULT', result: JSON.parse((e as MessageEvent).data) });
      es.close();
    });
    es.addEventListener('fail', (e) => {
      const msg = (() => { try { return JSON.parse((e as MessageEvent).data).message; } catch { return '运行失败'; } })();
      dispatch({ type: 'FAIL', message: String(msg) });
      es.close();
    });
    es.onerror = () => { dispatch({ type: 'FAIL', message: '连接中断' }); es.close(); };
  }, []);

  const reset = useCallback(() => { esRef.current?.close(); dispatch({ type: 'RESET' }); }, []);

  return { ...state, start, reset };
}

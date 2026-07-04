import { useState } from 'react';
import type { Params } from '../api/useRunStream';
import type { TaskType } from '../api/types';

const TYPES: { value: TaskType; label: string }[] = [
  { value: 'QA', label: '问答' },
  { value: 'SURVEY', label: '综述' },
  { value: 'DIGEST', label: '摘要' },
  { value: 'LEARNING_PATH', label: '学习路径' },
];

export function TaskForm({ disabled, onSubmit }: { disabled: boolean; onSubmit: (p: Params) => void }) {
  const [type, setType] = useState<TaskType>('QA');
  const [query, setQuery] = useState('');
  const canSubmit = !disabled && query.trim().length > 0;

  return (
    <form
      onSubmit={(e) => { e.preventDefault(); if (canSubmit) onSubmit({ type, query: query.trim() }); }}
    >
      <label>
        任务类型
        <select value={type} disabled={disabled} onChange={(e) => setType(e.target.value as TaskType)}>
          {TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
        </select>
      </label>
      <label>
        问题/主题
        <textarea
          value={query}
          disabled={disabled}
          rows={3}
          onChange={(e) => setQuery(e.target.value)}
        />
      </label>
      <button type="submit" disabled={!canSubmit}>提交</button>
    </form>
  );
}

import './app.css';
import { useRunStream } from './api/useRunStream';
import { TaskForm } from './components/TaskForm';
import { RunTimeline } from './components/RunTimeline';
import { ResultPanel } from './components/ResultPanel';

export default function App() {
  const run = useRunStream();
  const streaming = run.status === 'streaming';

  return (
    <main>
      <h1>Harness 学习小助手</h1>
      <TaskForm disabled={streaming} onSubmit={run.start} />

      {run.status === 'error' && (
        <div className="error-banner" role="alert">
          <span>{run.error}</span>
          <button type="button" onClick={run.reset}>重试</button>
        </div>
      )}

      <RunTimeline steps={run.steps} active={streaming} />
      {run.result && <ResultPanel result={run.result} />}
    </main>
  );
}

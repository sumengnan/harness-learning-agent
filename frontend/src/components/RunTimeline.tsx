import type { StepEvent } from '../api/types';

export function RunTimeline({ steps, active }: { steps: StepEvent[]; active: boolean }) {
  if (steps.length === 0 && !active) return null;
  return (
    <section aria-label="执行轨迹">
      {active && <p className="timeline-active">运行中…</p>}
      <ol className="timeline">
        {steps.map((s) => (
          <li key={s.seq} className="timeline-step">
            <span className={`layer-badge layer-${s.layer}`}>{s.layer}</span>
            <span className="step-event">{s.event}</span>
            <span className="step-detail">{s.detail}</span>
          </li>
        ))}
      </ol>
    </section>
  );
}

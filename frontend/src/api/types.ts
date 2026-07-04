export type TaskType = 'QA' | 'SURVEY' | 'DIGEST' | 'LEARNING_PATH';

export interface StepEvent { seq: number; layer: string; event: string; detail: string; }

export interface Artifact {
  id: string; runId: string; kind: string; key: string;
  content: string; meta: Record<string, string>;
}

export interface ResultEvent {
  success: boolean; output: string; evidence: Artifact[]; termination: string;
}

export type RunStatus = 'idle' | 'streaming' | 'done' | 'error';

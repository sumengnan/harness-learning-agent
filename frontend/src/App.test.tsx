import { render, screen } from '@testing-library/react';
import App from './App';

test('渲染标题', () => {
  render(<App />);
  expect(screen.getByRole('heading', { name: 'Harness 学习小助手' })).toBeInTheDocument();
});

import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ApiError } from '../../api/errors';
import { ProblemAlert } from './Alert';

describe('ProblemAlert', () => {
  it('shows the message, field errors and the support reference', () => {
    render(
      <ProblemAlert
        error={new ApiError({
          status: 400,
          code: 'VALIDATION_FAILED',
          message: 'Request validation failed',
          fieldErrors: [{ field: 'email', message: 'must be a well-formed email address' }],
          requestId: 'req-42',
        })}
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Request validation failed');
    expect(screen.getByText('email')).toBeInTheDocument();
    expect(screen.getByText(/req-42/)).toBeInTheDocument();
  });

  it('renders nothing without an error', () => {
    const { container } = render(<ProblemAlert error={null} />);
    expect(container).toBeEmptyDOMElement();
  });
});

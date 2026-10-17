import { useState } from 'react';
import { Button } from './Button';

export function CopyButton({ text, label = 'Copy' }: { text: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      setCopied(false);
    }
  };
  return (
    <Button size="sm" onClick={copy}>
      {copied ? 'Copied' : label}
    </Button>
  );
}

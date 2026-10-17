import { describe, expect, it } from 'vitest';
import { passwordProblem, safeNextPath } from './validation';

describe('safeNextPath (open-redirect guard)', () => {
  it('keeps same-site relative paths', () => {
    expect(safeNextPath('/account/orders?status=PAID')).toBe('/account/orders?status=PAID');
  });
  it('drops anything that could leave the site', () => {
    for (const bad of ['https://evil.example', '//evil.example', '/\\evil.example', 'javascript:alert(1)', '', null, undefined]) {
      expect(safeNextPath(bad)).toBe('/');
    }
  });
  it('drops paths with whitespace or control characters, which URL parsers strip into //host (F4)', () => {
    for (const bad of ['/\t/evil.example', '/\n/evil.example', '/\r/evil.example', '/ /evil.example', '/\u0000/evil.example', '/\u00a0/evil.example', '/orders\u2028']) {
      expect(safeNextPath(bad)).toBe('/');
    }
  });
  it('keeps percent-encoded characters, which stay on the site', () => {
    expect(safeNextPath('/backoffice/sales/orders?q=a%20b')).toBe('/backoffice/sales/orders?q=a%20b');
  });
});

describe('passwordProblem (mirrors the backend policy)', () => {
  it('accepts long passphrases', () => {
    expect(passwordProblem('correct horse battery staple', 'a@b.com')).toBeNull();
  });
  it('rejects short, too-long-in-bytes, or email passwords', () => {
    expect(passwordProblem('short')).not.toBeNull();
    expect(passwordProblem('é'.repeat(40))).not.toBeNull(); // 80 bytes > BCrypt's 72
    expect(passwordProblem('someone@example.com', 'someone@example.com')).not.toBeNull();
  });
});

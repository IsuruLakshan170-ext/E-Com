import { describe, expect, it } from 'vitest';
import { basisPointsToPercent, humanize, minorToInput, parseAmountToMinor, parsePercentToBasisPoints } from './format';

describe('money parsing (no floating point)', () => {
  it('turns typed amounts into exact minor units', () => {
    expect(parseAmountToMinor('19.99', 'USD')).toBe(1999);
    expect(parseAmountToMinor('19.9', 'USD')).toBe(1990);
    expect(parseAmountToMinor('19', 'USD')).toBe(1900);
    expect(parseAmountToMinor('0.07', 'USD')).toBe(7); // 0.07 * 100 in floats would be 7.000000000000001
    expect(parseAmountToMinor('1999', 'JPY')).toBe(1999);
    expect(parseAmountToMinor('1.999', 'KWD')).toBe(1999);
  });

  it('rejects anything that is not a plain amount', () => {
    for (const bad of ['', 'abc', '1.999', '-5', '1,000', '1e3']) {
      expect(parseAmountToMinor(bad, 'USD')).toBeNull();
    }
    expect(parseAmountToMinor('19.5', 'JPY')).toBeNull();
  });

  it('accepts a trailing dot while someone is still typing', () => {
    expect(parseAmountToMinor('12.', 'USD')).toBe(1200);
  });

  it('round-trips minor units to input text', () => {
    expect(minorToInput(1999, 'USD')).toBe('19.99');
    expect(minorToInput(5, 'USD')).toBe('0.05');
    expect(minorToInput(1999, 'JPY')).toBe('1999');
    expect(parseAmountToMinor(minorToInput(123456, 'USD'), 'USD')).toBe(123456);
  });
});

describe('tax rates', () => {
  it('parses percent to basis points and back', () => {
    expect(parsePercentToBasisPoints('10')).toBe(1000);
    expect(parsePercentToBasisPoints('10.5')).toBe(1050);
    expect(parsePercentToBasisPoints('0.25')).toBe(25);
    expect(parsePercentToBasisPoints('51')).toBeNull();
    expect(parsePercentToBasisPoints('abc')).toBeNull();
    expect(basisPointsToPercent(1050)).toBe('10.5%');
    expect(basisPointsToPercent(1000)).toBe('10%');
  });
});

describe('humanize', () => {
  it('makes constants readable', () => {
    expect(humanize('PENDING_PAYMENT')).toBe('Pending payment');
    expect(humanize(null)).toBe('—');
  });
});

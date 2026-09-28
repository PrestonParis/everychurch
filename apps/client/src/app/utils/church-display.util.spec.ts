import { titleCase } from './church-display.util';

describe('titleCase', () => {
  it('capitalizes each word in a bulk-imported all-caps name', () => {
    expect(titleCase('SAINT JOSEPH CHURCH')).toBe('Saint Joseph Church');
  });

  it('keeps the letter after an apostrophe lowercase — the reported bug', () => {
    expect(titleCase("ST. MARY'S CATHOLIC CHURCH")).toBe("St. Mary's Catholic Church");
  });

  it('capitalizes after a period and a hyphen', () => {
    expect(titleCase('ST. PAUL A.M.E. CHURCH')).toBe('St. Paul A.M.E. Church');
    expect(titleCase('CALVARY-BETHEL BAPTIST')).toBe('Calvary-Bethel Baptist');
  });

  it('handles a name that is already mixed case without mangling it further', () => {
    expect(titleCase('Grace Community Church')).toBe('Grace Community Church');
  });
});

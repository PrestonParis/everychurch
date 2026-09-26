export interface FaithSource {
  title: string;
  url: string;
}
export interface FaithOption {
  id: string;
  label: string;
  group?: string;
}
export interface FaithDimension {
  id: string;
  title: string;
  question: string;
  explanation: string;
  category: 'doctrine' | 'practice' | 'devotion';
  options: FaithOption[];
  sources: FaithSource[];
}
export interface FaithTradition {
  id: string;
  name: string;
  summary: string;
  answers: Record<string, string>;
  sources: FaithSource[];
}
export interface FaithCatalog {
  version: string;
  rulesVersion: string;
  notice: string;
  dimensions: FaithDimension[];
  traditions: FaithTradition[];
}
export interface FaithAnswer {
  option: string;
  importance: number;
}
export interface FaithEvaluation {
  catalogVersion: string;
  answers: Record<string, FaithAnswer>;
  city?: string;
  state?: string;
  latitude?: number;
  longitude?: number;
  radiusKm?: number;
}
export interface FaithStatement {
  option: string;
  note?: string;
  sourceUrl?: string;
}
export interface FaithProfileInput {
  catalogVersion: string;
  traditionId: string | null;
  answers: Record<string, FaithStatement>;
  participation: string;
  baseRevision: number | null;
}
export interface FaithProfile {
  churchId: number;
  churchName: string;
  revision: number | null;
  publishedAt: string | null;
  confirmedBy: string | null;
  content: FaithProfileInput | null;
}
export interface FaithEditor {
  draft: FaithProfile;
  published: FaithProfile;
  history: FaithProfile[];
}
export interface FaithDetail {
  dimensionId: string;
  title: string;
  userPosition: string;
  churchPosition: string;
  outcome: string;
  essential: boolean;
}
export interface FaithMatch {
  id: string;
  name: string;
  kind: string;
  status: string;
  doctrine: number | null;
  practices: number | null;
  coverage: number;
  lowerBound: number;
  upperBound: number;
  answeredTopics: number;
  comparedTopics: number;
  profileRevision: number | null;
  publishedAt: string | null;
  participation: string | null;
  details: FaithDetail[];
}
export interface FaithResult {
  catalogVersion: string;
  rulesVersion: string;
  evaluatedAt: string;
  guidance: string;
  traditions: FaithMatch[];
  churches: FaithMatch[];
  churchesEvaluated: number;
  locationProvided: boolean;
}
export interface FaithSaved {
  id: number;
  createdAt: string;
  request: FaithEvaluation;
  result: FaithResult;
}
export const practiceOptions: FaithOption[] = [
  { id: 'regular', label: 'Offered regularly' },
  { id: 'occasional', label: 'Offered occasionally' },
  { id: 'private', label: 'Encouraged privately' },
  { id: 'not_offered', label: 'Not offered locally' },
];
export function churchOptions(d: FaithDimension): FaithOption[] {
  return [
    ...(d.category === 'devotion' ? practiceOptions : d.options),
    { id: 'multiple', label: 'Multiple positions permitted' },
    { id: 'unstated', label: 'No stated position' },
    { id: 'unknown', label: 'Not provided' },
  ];
}

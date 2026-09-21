export interface ServiceTime {
  id?: number;
  dayOfWeek: string;
  time: string;
  type: string;
  language?: string;
}

export const DENOMINATIONS = [
  'Baptist',
  'Catholic',
  'Church of Christ',
  'Eastern Orthodox',
  'Episcopal/Anglican',
  'Evangelical',
  'Lutheran',
  'Methodist',
  'Non-denominational',
  'Pentecostal / Assembly of God',
  'Presbyterian',
  'Seventh-day Adventist',
  'Other',
];

/** Matches Spring's Page<T> JSON shape (org.springframework.data.domain.Page). */
export interface PagedResult<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface Church {
  id?: number;
  name: string;
  denomination?: string;
  address?: string;
  city?: string;
  state?: string;
  zipCode?: string;
  phone?: string;
  email?: string;
  website?: string;
  description?: string;
  imageUrl?: string;
  pastor?: string;
  facebook?: string;
  instagram?: string;
  youtube?: string;
  claimed?: boolean;
  latitude?: number;
  longitude?: number;
  serviceTimes?: ServiceTime[];
}

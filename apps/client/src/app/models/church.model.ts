export interface ServiceTime {
  id?: number;
  dayOfWeek: string;
  time: string;
  type: string;
  language?: string;
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
  serviceTimes?: ServiceTime[];
}

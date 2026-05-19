import axios from "axios";

export interface ApiErrorResponse {
  message?: string;
  error?: string;
  code?: string;
}

export const getApiErrorMessage = (error: unknown, fallback: string): string => {
  if (axios.isAxiosError<ApiErrorResponse>(error)) {
    return error.response?.data?.message || error.response?.data?.error || error.message || fallback;
  }
  if (error instanceof Error) {
    return error.message || fallback;
  }
  return fallback;
};

import axios from "axios";

export interface ApiErrorResponse {
  message?: string;
  error?: string;
  code?: string;
  requestId?: string;
}

export class HttpApiError extends Error {
  constructor(public readonly status: number, public readonly code: string | undefined, message: string) {
    super(message);
  }
}

export const getApiErrorCode = (error: unknown): string | undefined => {
  if (error instanceof HttpApiError) return error.code;
  if (axios.isAxiosError<ApiErrorResponse>(error) && typeof error.response?.data?.code === "string") return error.response.data.code;
  return undefined;
};

export const isRecoverableGameError = (code: string): boolean =>
  ["PHASE_CHANGED", "ALREADY_ACTED", "NOT_YOUR_TURN", "ROOM_FULL"].includes(code);

export const getApiErrorMessage = (error: unknown, fallback: string): string => {
  if (axios.isAxiosError<ApiErrorResponse>(error)) {
    return error.response?.data?.message || error.response?.data?.error || error.message || fallback;
  }
  if (error instanceof Error) {
    return error.message || fallback;
  }
  return fallback;
};

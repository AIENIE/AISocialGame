export interface GameConfigOption {
  id: string;
  label: string;
  type: "select" | "number" | "boolean" | "text";
  defaultValue: any;
  options?: { label: string; value: any }[];
  min?: number;
  max?: number;
}

export type GameStatus = "ACTIVE" | "MAINTENANCE" | "COMING_SOON";

export interface Game {
  id: string;
  name: string;
  description: string;
  coverUrl: string;
  tags: string[];
  minPlayers: number;
  maxPlayers: number;
  status: GameStatus | "active" | "maintenance" | "coming_soon";
  onlineCount: number;
  configSchema: GameConfigOption[];
  engineBacked?: boolean;
  phaseDefinitions?: PhaseDefinition[];
  roleDefinitions?: RoleDefinition[];
}

export interface User {
  id: string;
  externalUserId?: number;
  username?: string;
  nickname: string;
  email?: string;
  avatar: string;
  coins?: number;
  balanceAvailable?: boolean;
  level?: number;
  balance?: {
    publicPermanentTokens: number;
    projectTempTokens: number;
    projectPermanentTokens: number;
    totalTokens: number;
    projectTempExpiresAt?: string;
  };
}

export interface Persona {
  id: string;
  name: string;
  trait: string;
  avatar: string;
  speechStyle?: string;
  strategyStyle?: string;
  difficultyLevel?: number;
  memorySeed?: string;
  riskPreference?: number;
  emotionalRecovery?: number;
  sociability?: number;
  behaviorGuide?: Partial<Record<"questioning" | "stance" | "revision" | "commitment", string>>;
  presetVersion?: number;
}

export interface RoomSeat {
  seatNumber: number;
  playerId: string;
  displayName: string;
  ai: boolean;
  personaId?: string;
  avatar?: string;
  ready: boolean;
  host: boolean;
}

export interface Room {
  id: string;
  gameId: string;
  name: string;
  status: "WAITING" | "PLAYING" | "EXPIRED" | "waiting" | "playing";
  roomCode?: string;
  waitingSince?: string;
  expiresAt?: string | null;
  maxPlayers: number;
  seatCount?: number;
  isPrivate: boolean;
  commMode?: string;
  config?: Record<string, any>;
  spectateAllowed?: boolean;
  spectatorCount?: number;
  seats: RoomSeat[];
  selfPlayerId?: string;
  hostUserId?: string;
}

export interface RoomEntry {
  id: string;
  roomCode: string;
  gameId: string;
  name: string;
  status: "WAITING" | "PLAYING";
  isPrivate: boolean;
  seatCount: number;
  maxPlayers: number;
  passwordRequired: boolean;
  joined: boolean;
  expiresAt: string | null;
}

export interface AuthResponse {
  token: string;
  user: User;
}

export interface SsoCallbackData {
  code: string;
  redirect: string;
}

export interface AiMessage {
  role: string;
  content: string;
}

export interface AiChatResponse {
  content: string;
  modelKey: string;
  promptTokens: number;
  completionTokens: number;
}

export interface AiEmbeddingsResponse {
  modelKey: string;
  dimensions: number;
  embeddings: number[][];
  promptTokens: number;
}

export interface AiOcrParams {
  imageUrl?: string;
  imageBase64?: string;
  documentUrl?: string;
  model?: string;
  pages?: string;
  outputType?: "TEXT" | "MARKDOWN" | "JSON";
}

export interface AiOcrResponse {
  requestId: string;
  modelKey: string;
  outputType: string;
  content: string;
  rawJson?: string;
}

export interface AiModel {
  id: number;
  displayName: string;
  provider: string;
  inputRate: number;
  outputRate: number;
  type: string;
}

export interface AdminAuthResponse {
  username: string;
  displayName: string;
  sessionScope: "FULL" | "RECOVERY_REBIND_ONLY";
  authMode: "password" | "totp";
  recoveryCodesRemaining?: number;
  expiresAt: string;
}

export interface AdminAuthPolicy {
  env: "local" | "test" | "production";
  authMode: "password" | "totp";
  totpRequired: boolean;
  enrollmentStatus: "ENROLLED" | "NOT_ENROLLED" | "NOT_APPLICABLE";
}

export interface AdminLoginResult {
  state: "AUTHENTICATED" | "TOTP_REQUIRED" | "ENROLLMENT_REQUIRED";
  challengeId?: string;
  challengeExpiresAt?: string;
  username?: string;
  displayName?: string;
  sessionScope?: "FULL" | "RECOVERY_REBIND_ONLY";
  expiresAt?: string;
  recoveryCodes?: string[];
}

export interface AdminEnrollmentStart {
  challengeId: string;
  otpauthUri: string;
  manualKey: string;
  expiresAt: string;
}

export interface AdminIntegrationStatus {
  services: {
    service: string;
    reachable: boolean;
    message: string;
  }[];
}

export interface AdminRedeemCode {
  id: number;
  code: string;
  creditType: string;
  tokens: number;
  active: boolean;
  validFrom?: string;
  validUntil?: string;
  maxRedemptions?: number;
  redeemedCount: number;
}

export interface AdminAiDecisionTrace {
  id: number;
  roomId?: string;
  gameId: string;
  phase?: string;
  roundNumber: number;
  action: string;
  actorPlayerId?: string;
  personaId?: string;
  roleKey?: string;
  modelKey?: string;
  latencyMs: number;
  fallback: boolean;
  validDecision: boolean;
  confidence?: number;
  targetPlayerId?: string;
  nightAction?: string;
  reason?: string;
  outputSummary?: string;
  inputSummary?: string;
  quality?: Record<string, any>;
  beliefSnapshot?: Record<string, any>;
  memorySnapshot?: Record<string, any>;
  createdAt?: string;
}

export interface AdminAiPersonaMemory {
  id: number;
  personaId: string;
  gameId: string;
  roleKey: string;
  memorySummary?: string;
  strategyNotes?: string;
  mistakeNotes?: string;
  speechPatterns?: string;
  gamesPlayed: number;
  approvedSummary?: string;
  reviewStatus?: "PENDING" | "APPROVED" | "REJECTED";
  updatedAt?: string;
}

export type SafetyAction = "ALLOW" | "REDACT" | "BLOCK" | "RATE_LIMIT" | "ESCALATE";
export type SafetyEventStatus = "OPEN" | "ACKED" | "CLOSED";

export interface AiSafetySummary {
  openHighRiskEvents: number;
  blockedLast24h: number;
  costAnomaliesLast24h: number;
  activeControls: number;
}

export interface AiSafetyEvent {
  id: number;
  source: string;
  action: SafetyAction;
  severity: "LOW" | "MEDIUM" | "HIGH";
  category: string;
  status: SafetyEventStatus;
  roomId?: string;
  gameId?: string;
  userId?: string;
  playerId?: string;
  personaId?: string;
  modelKey?: string;
  traceId?: string;
  contentSummary?: string;
  sanitizedContent?: string;
  reason?: string;
  metadata?: Record<string, any>;
  acknowledgedBy?: string;
  acknowledgedAt?: string;
  closedBy?: string;
  closedAt?: string;
  closeReason?: string;
  createdAt?: string;
}

export interface AiSafetyControl {
  id: number;
  scope: "USER" | "ROOM" | "PERSONA" | "MODEL" | "GLOBAL";
  targetKey: string;
  action: SafetyAction;
  reason?: string;
  createdBy?: string;
  active: boolean;
  expiresAt?: string;
  createdAt?: string;
}

export interface CheckinResponse {
  success: boolean;
  tokensGranted: number;
  alreadyCheckedIn: boolean;
  errorMessage?: string;
  balance: {
    publicPermanentTokens: number;
    projectTempTokens: number;
    projectPermanentTokens: number;
    totalTokens: number;
    projectTempExpiresAt?: string;
  };
}

export interface CheckinStatusResponse {
  checkedInToday: boolean;
  lastCheckinDate?: string;
  tokensGrantedToday: number;
}

export interface UsageRecord {
  requestId: string;
  modelKey: string;
  promptTokens: number;
  completionTokens: number;
  billedTokens: number;
  createdAt?: string;
}

export interface LedgerEntry {
  id: string;
  type: string;
  tokens: number;
  reason?: string;
  createdAt?: string;
}

export interface RedeemResponse {
  success: boolean;
  tokensGranted: number;
  creditType: string;
  errorMessage?: string;
  balance: {
    publicPermanentTokens: number;
    projectTempTokens: number;
    projectPermanentTokens: number;
    totalTokens: number;
    projectTempExpiresAt?: string;
  };
}

export interface ExchangeResponse {
  success: boolean;
  requestId: string;
  exchangedTokens: number;
  balance: {
    publicPermanentTokens: number;
    projectTempTokens: number;
    projectPermanentTokens: number;
    totalTokens: number;
    projectTempExpiresAt?: string;
  };
}

export interface ExchangeHistoryRecord {
  requestId: string;
  exchangedTokens: number;
  publicBefore: number;
  publicAfter: number;
  projectPermanentBefore: number;
  projectPermanentAfter: number;
  createdAt?: string;
}

export interface RedemptionRecord {
  code: string;
  tokensGranted: number;
  creditType: string;
  redeemedAt?: string;
}

export interface PagedResponse<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface GameLogEntry {
  type: string;
  message: string;
  time: string;
  actorId?: string;
  targetId?: string;
  phase?: string;
  roundNumber?: number;
  metadata?: Record<string, unknown> & {
    presentation?: AiPresentation;
    eventId?: string;
    publicSeq?: number;
    content?: string;
    correlationId?: string;
    voteResult?: { votes: Record<string, string>; tally: Record<string, number> };
  };
}

export interface GameLogPage {
  items: GameLogEntry[];
  nextCursor: number | null;
  hasMore: boolean;
}

export interface AiPresentation {
  emotion?: "neutral" | "curious" | "tense" | "relieved" | "uncertain" | "amused" | "determined" | "disappointed";
  gesture?: "none" | "pause" | "nod" | "shake_head" | "frown" | "smile" | "sigh" | "lean_in";
  intensity?: 0 | 1 | 2 | 3;
}

export interface GamePlayerStateView {
  playerId: string;
  displayName: string;
  seatNumber: number;
  ai: boolean;
  personaId?: string;
  avatar?: string;
  alive: boolean;
  role?: string;
  word?: string;
  connectionStatus?: "ONLINE" | "DISCONNECTED" | "AI_TAKEOVER";
  presentation?: AiPresentation;
}

export interface PendingAction {
  type: string;
  description: string;
  deadlineSeconds: number;
}

export interface PhaseDefinition {
  phase: string;
  displayName: string;
  defaultDurationSeconds: number;
  allowChat: boolean;
}

export interface RoleDefinition {
  role: string;
  displayName: string;
  faction: string;
  hasNightAction: boolean;
}

export interface PlayerAction {
  type: "SPEAK" | "VOTE" | "NIGHT_ACTION" | "ASK_QUESTION" | "SUBMIT_SOLUTION"
    | "ASK_PLAYER" | "ANSWER_PLAYER" | "SKIP" | "HUNTER_SHOOT" | "DISCUSS" | "REQUEST_HINT" | "REVEAL_SOLUTION";
  content?: string;
  targetPlayerId?: string;
  abstain?: boolean;
  nightAction?: string;
  useHeal?: boolean;
  extra?: Record<string, any>;
  requestId?: string;
  expectedPhaseToken?: string;
}

export interface LegalAction {
  type: PlayerAction["type"];
  label: string;
  nightAction?: string | null;
  targets: string[];
  maxLength: number;
}

export interface GameState {
  roomId: string;
  gameId: string;
  phase: string;
  round: number;
  currentSeat?: number;
  currentSpeakerName?: string;
  winner?: string;
  myPlayerId?: string;
  mySeatNumber?: number;
  myWord?: string;
  myRole?: string;
  phaseEndsAt?: string;
  players: GamePlayerStateView[];
  logs: GameLogEntry[];
  extra?: Record<string, any>;
  votes?: Record<string, string>;
  pendingAction?: PendingAction | null;
}

export interface GameStateEvent {
  type: string;
  phase: string;
  round: number;
  currentSeat?: number;
  payload?: Record<string, any>;
}

export interface PrivateEvent {
  type: string;
  payload?: Record<string, any>;
}

export interface SeatEvent {
  type: string;
  seat: RoomSeat;
}

export interface ChatMessage {
  id: string;
  roomId: string;
  senderId: string;
  senderName: string;
  senderAvatar?: string;
  type: "TEXT" | "EMOJI" | "QUICK_PHRASE" | "SYSTEM";
  content: string;
  timestamp: number;
}

export interface CommunityPost {
  id: string;
  authorName: string;
  authorId?: string;
  avatar?: string;
  content: string;
  tags: string[];
  likes: number;
  comments: number;
  createdAt: string;
}

export interface PlayerStats {
  id: string;
  playerId: string;
  gameId: string;
  displayName: string;
  avatar?: string;
  gamesPlayed: number;
  wins: number;
  score: number;
}

export interface FriendItem {
  id: string;
  displayName: string;
  avatar?: string;
  online: boolean;
  currentGameId?: string;
  currentRoomId?: string;
}

export interface FriendRequestItem {
  id: string;
  fromId: string;
  fromName: string;
  fromAvatar?: string;
  createdAt: string;
}

export interface AchievementDefinition {
  code: string;
  name: string;
  description: string;
  rarity: "COMMON" | "RARE" | "EPIC";
  rewardCoins: number;
  gameId?: string;
  target: number;
}

export interface PlayerAchievement {
  code: string;
  unlocked: boolean;
  progress: number;
  unlockedAt?: string;
}

export interface ReplayEvent {
  phase?: string;
  seq?: number;
  id: string;
  timestamp: string;
  type: string;
  message: string;
}

export interface ReplayArchive {
  id: string;
  gameId: string;
  roomId: string;
  roomName: string;
  result: string;
  perspective: "GOD" | "PLAYER";
  createdAt: string;
  events: ReplayEvent[];
}

export type ReplayViewMode = "PUBLIC" | "PLAYER" | "GOD";

export interface ReplayArchiveView {
  id: string;
  roomId: string;
  gameId: string;
  roomName: string;
  winner?: string;
  playerCount: number;
  totalRounds: number;
  durationSeconds: number;
  eventCount: number;
  summary?: string;
  aiQualitySummary?: Record<string, any>;
  startedAt?: string;
  finishedAt?: string;
  createdAt?: string;
}

export interface ReplayStructuredEvent {
  id: number;
  archiveId: string;
  seq: number;
  eventType: string;
  phase?: string;
  roundNumber: number;
  actorPlayerId?: string;
  targetPlayerId?: string;
  visibility: ReplayViewMode;
  data: Record<string, any>;
  occurredAt?: string;
}

export interface ReplayDetail {
  availableViews?: ReplayViewMode[];
  archive: ReplayArchiveView;
  viewMode: ReplayViewMode;
  events: ReplayStructuredEvent[];
}

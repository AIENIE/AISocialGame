import { lazy, type ComponentType, type LazyExoticComponent } from "react";

export const gameRoomComponents: Record<string, LazyExoticComponent<ComponentType>> = {
  undercover: lazy(() => import("./UndercoverRoom")),
  werewolf: lazy(() => import("./WerewolfRoom")),
  turtle_soup: lazy(() => import("./TurtleSoupRoom")),
};

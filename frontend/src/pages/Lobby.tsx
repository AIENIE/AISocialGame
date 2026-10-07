import { Suspense } from "react";
import { useParams } from "react-router-dom";
import { gameRoomComponents } from "./games/registry";
import { DataState } from "@/components/DataState";
const Lobby = () => {
 const { gameId } = useParams();
 const Room = gameId ? gameRoomComponents[gameId] : undefined;
 return Room ? <Suspense fallback={<DataState loading />}><Room /></Suspense> : <DataState />;
};
export default Lobby;

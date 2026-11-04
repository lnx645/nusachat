%%%-------------------------------------------------------------------
%%% @doc Amplop event WebSocket (murni, tanpa I/O).
%%%
%%% Bentuk kabel v1 (dipakai Android + ws_smoke.py, jangan diubah tanpa
%%% versionalisasi):
%%% ```
%%% {"event_id":123,"type":"message","data":{...},"ts":"2026-..Z"}
%%% '''
%%% `type'/`data' disalin dari payload outbox PHP
%%% (`{"type":..,"data":..}'); payload rusak → `{type=event, data=null}'
%%% agar satu baris busuk tidak mematikan klien.
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_envelope).

-export([encode/3]).

%%%-------------------------------------------------------------------
%%% @doc Bungkus satu baris outbox menjadi JSON kabel. `Payload' adalah
%%% JSON mentah dari PHP (binary/iolist); `CreatedAt' dinormalisasi ke
%%% binary (MySQL bisa mengembalikan list).
%%% @end
%%%-------------------------------------------------------------------
-spec encode(integer(), iodata(), term()) -> binary().
encode(Id, Payload, CreatedAt) ->
    {Type, Data} = try jsx:decode(iolist_to_binary(Payload), [return_maps]) of
        #{<<"type">> := T} = M -> {T, maps:get(<<"data">>, M, null)};
        _ -> {<<"event">>, null}
    catch
        _:_ -> {<<"event">>, null}
    end,
    Ts = case CreatedAt of
        Bin when is_binary(Bin) -> Bin;
        List when is_list(List) -> list_to_binary(List);
        _ -> <<>>
    end,
    jsx:encode(#{<<"event_id">> => Id,
                 <<"type">> => Type,
                 <<"data">> => Data,
                 <<"ts">> => Ts}).

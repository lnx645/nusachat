%%%-------------------------------------------------------------------
%%% @doc Uji murni relay voice NVP/1 (tanpa socket).
%%% Forward UDP end-to-end dicover smoke test.
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_voice_relay_tests).

-include_lib("eunit/include/eunit.hrl").

-define(HDR(CallId, Slot, Flags, Token, Seq, Ts),
        <<16#4E:8, 16#01:8, CallId:32, Slot:8, Flags:8, Token:32,
          Seq:16, Ts:32>>).

parse_media_ok_test() ->
    Payload = binary:copy(<<7>>, 60),
    Tag = binary:copy(<<9>>, 16),
    Bin = <<(?HDR(11, 2, 0, 12345, 100, 9999))/binary, Payload/binary,
            Tag/binary>>,
    {ok, H} = nusa_voice_relay:parse_header(Bin),
    11 = maps:get(call_id, H),
    2 = maps:get(slot, H),
    12345 = maps:get(token, H),
    100 = maps:get(seq, H),
    9999 = maps:get(ts, H),
    76 = byte_size(maps:get(payload, H)).

parse_keepalive_ok_test() ->
    {ok, H} = nusa_voice_relay:parse_header(?HDR(11, 0, 16#04, 1, 2, 3)),
    <<>> = maps:get(payload, H).

parse_rejects_test() ->
    %% Magic salah.
    {error, bad_header} =
        nusa_voice_relay:parse_header(
          <<16#00:8, 16#01:8, 11:32, 0:8, 0:8, 1:32, 2:16, 3:32,
            (binary:copy(<<0>>, 16))/binary>>),
    %% Ver salah.
    {error, bad_header} =
        nusa_voice_relay:parse_header(
          <<16#4E:8, 16#02:8, 11:32, 0:8, 0:8, 1:32, 2:16, 3:32,
            (binary:copy(<<0>>, 16))/binary>>),
    %% Terlalu besar.
    {error, too_big} =
        nusa_voice_relay:parse_header(
          <<(?HDR(11, 0, 0, 1, 2, 3))/binary,
            (binary:copy(<<0>>, 1200))/binary>>),
    %% Media tanpa tag.
    {error, short_payload} =
        nusa_voice_relay:parse_header(
          <<(?HDR(11, 0, 0, 1, 2, 3))/binary, 1, 2, 3>>),
    %% Keepalive berpayload.
    {error, keepalive_with_payload} =
        nusa_voice_relay:parse_header(
          <<(?HDR(11, 0, 16#04, 1, 2, 3))/binary, 1>>),
    %% Terpotong.
    {error, bad_header} = nusa_voice_relay:parse_header(<<1, 2, 3>>).

targets_test() ->
    %% Normal: semua kecuali pengirim.
    [0, 2] = nusa_voice_relay:targets([0, 1, 2], false, 1),
    [] = nusa_voice_relay:targets([3], false, 3),
    %% Echo: pengirim saja.
    [1] = nusa_voice_relay:targets([0, 1, 2], true, 1).

bucket_allows_burst_then_denies_test() ->
    {allow, 149} = nusa_voice_relay:bucket(150, 1000, 1000),
    {deny, 0} = nusa_voice_relay:bucket(0, 1000, 1000).

bucket_refills_over_time_test() ->
    %% 500 ms × 100/detik = 50 token, capped 150.
    {allow, 49} = nusa_voice_relay:bucket(0, 1000, 1500),
    {allow, 149} = nusa_voice_relay:bucket(140, 1000, 5000).

call_token_stable_test() ->
    T1 = nusa_voice_relay:call_token(11, 7),
    T1 = nusa_voice_relay:call_token(11, 7),
    true = is_integer(T1) andalso T1 >= 0 andalso T1 < 4294967296,
    %% User/call lain → token beda (tabrakan 2^-32, aman diuji).
    true = nusa_voice_relay:call_token(11, 8) =/= T1,
    true = nusa_voice_relay:call_token(12, 7) =/= T1.

room_slot_test() ->
    0 = nusa_voice_relay:room_slot([5, 9, 2], 5),
    2 = nusa_voice_relay:room_slot([5, 9, 2], 2),
    -1 = nusa_voice_relay:room_slot([5, 9], 7),
    -1 = nusa_voice_relay:room_slot([], 7).

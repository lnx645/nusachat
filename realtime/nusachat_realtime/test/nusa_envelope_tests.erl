%%%-------------------------------------------------------------------
%%% @doc Unit test nusa_envelope: bentuk kabel v1 + ketahanan terhadap
%%% payload rusak. Bentuk ini dikonsumsi Android — ubah hanya dengan
%%% versionalisasi.
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_envelope_tests).

-include_lib("eunit/include/eunit.hrl").

decode(Bin) -> jsx:decode(Bin, [return_maps]).

shape_test() ->
    Out = decode(nusa_envelope:encode(
                   123, <<"{\"type\":\"message\",\"data\":{\"a\":1}}">>,
                   <<"2026-01-01T00:00:00Z">>)),
    ?assertEqual(123, maps:get(<<"event_id">>, Out)),
    ?assertEqual(<<"message">>, maps:get(<<"type">>, Out)),
    ?assertEqual(#{<<"a">> => 1}, maps:get(<<"data">>, Out)),
    ?assertEqual(<<"2026-01-01T00:00:00Z">>, maps:get(<<"ts">>, Out)),
    ok.

missing_data_null_test() ->
    Out = decode(nusa_envelope:encode(7, <<"{\"type\":\"ping\"}">>, <<>>)),
    ?assertEqual(null, maps:get(<<"data">>, Out)),
    ok.

garbage_payload_test() ->
    Out = decode(nusa_envelope:encode(9, <<"bukan-json{{{">>, <<>>)),
    ?assertEqual(<<"event">>, maps:get(<<"type">>, Out)),
    ?assertEqual(null, maps:get(<<"data">>, Out)),
    ok.

non_object_payload_test() ->
    Out = decode(nusa_envelope:encode(9, <<"[1,2]">>, <<>>)),
    ?assertEqual(<<"event">>, maps:get(<<"type">>, Out)),
    ok.

ts_list_test() ->
    Out = decode(nusa_envelope:encode(1, <<"{\"type\":\"t\"}">>, "2026-02-02T02:02:02Z")),
    ?assertEqual(<<"2026-02-02T02:02:02Z">>, maps:get(<<"ts">>, Out)),
    ok.

ts_other_test() ->
    Out = decode(nusa_envelope:encode(1, <<"{\"type\":\"t\"}">>, undefined)),
    ?assertEqual(<<>>, maps:get(<<"ts">>, Out)),
    ok.

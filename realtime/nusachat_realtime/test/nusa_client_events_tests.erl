%%%-------------------------------------------------------------------
%%% @doc Unit test nusa_client_events: validasi frame murni.
%%% Jalur DB-nya diuji ws_smoke.py (gateway + MySQL hidup).
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_client_events_tests).

-include_lib("eunit/include/eunit.hrl").

ack_ok_test() ->
    ?assertEqual({ok, {ack, 9, 42}},
                 nusa_client_events:parse_frame(
                   <<"message.ack">>,
                   #{<<"conversation_id">> => 9, <<"message_id">> => 42})),
    ok.

read_ok_test() ->
    ?assertEqual({ok, {read, 9, 42}},
                 nusa_client_events:parse_frame(
                   <<"message.read">>,
                   #{<<"conversation_id">> => 9, <<"message_id">> => 42})),
    ok.

typing_ok_test() ->
    ?assertEqual({ok, {typing, 9}},
                 nusa_client_events:parse_frame(
                   <<"chat.typing">>, #{<<"conversation_id">> => 9})),
    ok.

bad_ids_test() ->
    ?assertMatch({error, _},
                 nusa_client_events:parse_frame(
                   <<"message.ack">>, #{<<"conversation_id">> => 0})),
    ?assertMatch({error, _},
                 nusa_client_events:parse_frame(
                   <<"message.read">>,
                   #{<<"conversation_id">> => 9, <<"message_id">> => -1})),
    ?assertMatch({error, _},
                 nusa_client_events:parse_frame(
                   <<"message.ack">>,
                   #{<<"conversation_id">> => <<"9">>, <<"message_id">> => 42})),
    ?assertMatch({error, _},
                 nusa_client_events:parse_frame(<<"chat.typing">>, #{})),
    ok.

unknown_type_test() ->
    ?assertMatch({error, _},
                 nusa_client_events:parse_frame(<<"message.send">>, #{})),
    ok.

%%%-------------------------------------------------------------------
%%% @doc Uji murni signaling `nusa.call.v1' (tanpa DB/Socket).
%%% Jalur DB dicover smoke test WS.
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_call_tests).

-include_lib("eunit/include/eunit.hrl").

offer_ok_test() ->
    {ok, {offer, 9, [<<"opus">>, <<"amr-wb">>], <<"a2V5">>, <<"c2FsdA">>}} =
        nusa_call:parse_frame(
          <<"call.offer">>,
          #{<<"to_user_id">> => 9,
            <<"codecs">> => [<<"opus">>, <<"amr-wb">>],
            <<"key_ct">> => <<"a2V5">>,
            <<"salt">> => <<"c2FsdA">>}).

offer_rejects_test() ->
    {error, <<"invalid_target">>, _} =
        nusa_call:parse_frame(<<"call.offer">>, #{<<"to_user_id">> => 0}),
    {error, <<"invalid_codecs">>, _} =
        nusa_call:parse_frame(
          <<"call.offer">>,
          #{<<"to_user_id">> => 9, <<"codecs">> => [<<"g729">>],
            <<"key_ct">> => <<"k">>, <<"salt">> => <<"s">>}),
    {error, <<"invalid_codecs">>, _} =
        nusa_call:parse_frame(
          <<"call.offer">>,
          #{<<"to_user_id">> => 9, <<"codecs">> => [],
            <<"key_ct">> => <<"k">>, <<"salt">> => <<"s">>}),
    {error, <<"invalid_key">>, _} =
        nusa_call:parse_frame(
          <<"call.offer">>,
          #{<<"to_user_id">> => 9, <<"codecs">> => [<<"opus">>],
            <<"salt">> => <<"s">>}),
    {error, <<"invalid_kind">>, _} =
        nusa_call:parse_frame(
          <<"call.offer">>,
          #{<<"to_user_id">> => 9, <<"codecs">> => [<<"opus">>],
            <<"key_ct">> => <<"k">>, <<"salt">> => <<"s">>,
            <<"kind">> => <<"video">>}).

answer_ok_test() ->
    {ok, {answer, 5, true, <<"opus">>}} =
        nusa_call:parse_frame(
          <<"call.answer">>,
          #{<<"call_id">> => 5, <<"accept">> => true,
            <<"codec">> => <<"opus">>}),
    {ok, {answer, 5, false, <<"opus">>}} =
        nusa_call:parse_frame(
          <<"call.answer">>,
          #{<<"call_id">> => 5, <<"accept">> => false,
            <<"codec">> => <<"opus">>}).

answer_rejects_test() ->
    {error, <<"invalid_call">>, _} =
        nusa_call:parse_frame(
          <<"call.answer">>,
          #{<<"accept">> => true, <<"codec">> => <<"opus">>}),
    {error, <<"invalid_accept">>, _} =
        nusa_call:parse_frame(
          <<"call.answer">>,
          #{<<"call_id">> => 5, <<"codec">> => <<"opus">>}),
    {error, <<"invalid_codec">>, _} =
        nusa_call:parse_frame(
          <<"call.answer">>,
          #{<<"call_id">> => 5, <<"accept">> => true,
            <<"codec">> => <<"g729">>}).

id_actions_test() ->
    {ok, {reject, 7}} =
        nusa_call:parse_frame(<<"call.reject">>, #{<<"call_id">> => 7}),
    {ok, {room_join, 7}} =
        nusa_call:parse_frame(<<"call.room_join">>, #{<<"call_id">> => 7}),
    {ok, {room_leave, 7}} =
        nusa_call:parse_frame(<<"call.room_leave">>, #{<<"call_id">> => 7}),
    {error, <<"invalid_call">>, _} =
        nusa_call:parse_frame(<<"call.reject">>, #{<<"call_id">> => 0}).

end_defaults_reason_test() ->
    {ok, {hangup, 7, <<"hangup">>}} =
        nusa_call:parse_frame(<<"call.end">>, #{<<"call_id">> => 7}),
    {ok, {hangup, 7, <<"missed">>}} =
        nusa_call:parse_frame(
          <<"call.end">>, #{<<"call_id">> => 7, <<"reason">> => <<"missed">>}).

echo_test() ->
    {ok, {echo, 7, true}} =
        nusa_call:parse_frame(
          <<"call.echo">>, #{<<"call_id">> => 7, <<"on">> => true}),
    {ok, {echo, 9, false}} =
        nusa_call:parse_frame(
          <<"call.echo">>, #{<<"call_id">> => 9, <<"on">> => false}),
    {error, <<"invalid_echo">>, _} =
        nusa_call:parse_frame(<<"call.echo">>, #{<<"call_id">> => 9}).

unknown_type_test() ->
    {error, <<"unknown_type">>, _} =
        nusa_call:parse_frame(<<"call.nonsense">>, #{}).

row_to_call_test() ->
    Call = nusa_call:row_to_call(
             [5, <<"uuid-1">>, 1, 2, null, <<"audio">>, <<"ringing">>,
              <<"2026-11-05T00:00:00Z">>, <<"2026-11-05T00:00:00Z">>,
              null, null]),
    5 = maps:get(<<"id">>, Call),
    null = maps:get(<<"conversation_id">>, Call),
    <<"ringing">> = maps:get(<<"status">>, Call),
    %% MySQL kadang list: tetap binary.
    Call2 = nusa_call:row_to_call(
              ["6", "uuid-2", "1", "2", null, "audio", "active",
               "2026-11-05T00:00:00Z", "2026-11-05T00:00:00Z", null, null]),
    6 = maps:get(<<"id">>, Call2),
    <<"active">> = maps:get(<<"status">>, Call2).

row_to_user_test() ->
    U = nusa_call:row_to_user([2, <<"nick">>, <<"Nick">>, null]),
    2 = maps:get(<<"id">>, U),
    <<"Nick">> = maps:get(<<"display_name">>, U),
    null = maps:get(<<"avatar_url">>, U).

now_iso_shape_test() ->
    Now = nusa_call:now_iso(),
    20 = byte_size(Now),
    $T = binary:at(Now, 10),
    $Z = binary:at(Now, 19).

uuid4_shape_test() ->
    U = nusa_call:uuid4(),
    36 = byte_size(U),
    $- = binary:at(U, 8),
    $- = binary:at(U, 13),
    $- = binary:at(U, 18),
    $- = binary:at(U, 23),
    $4 = binary:at(U, 14),
    true = lists:member(binary:at(U, 19), [$8, $9, $a, $b]),
    %% Unik praktis.
    true = nusa_call:uuid4() =/= U.

%%%-------------------------------------------------------------------
%%% @doc Unit test nusa_fanout: format cutoff penyapu relay.
%%% Cutoff dipakai di klausa `expires_at < ?' — harus ISO-8601 UTC
%%% agar perbandingan string VARCHAR konsisten di MySQL.
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_fanout_tests).

-include_lib("eunit/include/eunit.hrl").

format_test() ->
    Cut = nusa_fanout:cutoff_seconds_back(0),
    ?assertEqual(20, byte_size(Cut)),
    ?assertMatch({_, _}, {binary:at(Cut, 4), binary:at(Cut, 10)}),
    ?assertEqual($T, binary:at(Cut, 10)),
    ?assertEqual($Z, binary:at(Cut, 19)),
    ok.

ordering_test() ->
    Now = nusa_fanout:cutoff_seconds_back(0),
    Day = nusa_fanout:cutoff_seconds_back(86400),
    ?assert(Now > Day),
    ok.

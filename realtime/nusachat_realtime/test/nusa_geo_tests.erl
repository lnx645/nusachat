%%%-------------------------------------------------------------------
%%% @doc Unit test nusa_geo: koordinat, radius, haversine, konversi.
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_geo_tests).

-include_lib("eunit/include/eunit.hrl").

valid_coords_test() ->
    ?assert(nusa_geo:valid_coords(-6.2, 106.8)),
    ?assert(nusa_geo:valid_coords(-90, -180)),
    ?assert(nusa_geo:valid_coords(90, 180)),
    ?assertNot(nusa_geo:valid_coords(-90.1, 0)),
    ?assertNot(nusa_geo:valid_coords(0, 180.1)),
    ?assertNot(nusa_geo:valid_coords(<<"-6.2">>, 106.8)),
    ?assertNot(nusa_geo:valid_coords(undefined, undefined)),
    ok.

clamp_radius_test() ->
    ?assertEqual(25, nusa_geo:clamp_radius(25)),
    ?assertEqual(1, nusa_geo:clamp_radius(0)),
    ?assertEqual(1, nusa_geo:clamp_radius(-50)),
    ?assertEqual(100, nusa_geo:clamp_radius(101)),
    ?assertEqual(100, nusa_geo:clamp_radius(1.0e9)),
    ?assertEqual(50, nusa_geo:clamp_radius(50.4)),
    ?assertEqual(25, nusa_geo:clamp_radius(<<"x">>)),
    ?assertEqual(25, nusa_geo:clamp_radius(undefined)),
    ok.

%% Jakarta (-6.2,106.816) ke Bandung (-6.917,107.619) ≈ 116 km.
haversine_test() ->
    ?assertEqual(0.0, nusa_geo:haversine(-6.2, 106.8, -6.2, 106.8)),
    D = nusa_geo:haversine(-6.2, 106.816, -6.917, 107.619),
    ?assert(D > 110.0 andalso D < 122.0),
    ok.

round3_test() ->
    ?assertEqual(1.235, nusa_geo:round3(1.23456)),
    ?assertEqual(-6.2, nusa_geo:round3(-6.20004)),
    ok.

to_float_test() ->
    ?assertEqual(1.5, nusa_geo:to_float(1.5)),
    ?assertEqual(2.0, nusa_geo:to_float(2)),
    ?assertEqual(1.5, nusa_geo:to_float(<<"1.5">>)),
    ?assertEqual(3.0, nusa_geo:to_float(<<"3">>)),
    ?assertEqual(undefined, nusa_geo:to_float(<<"x">>)),
    ?assertEqual(undefined, nusa_geo:to_float(undefined)),
    ?assertEqual(undefined, nusa_geo:to_float(null)),
    ok.

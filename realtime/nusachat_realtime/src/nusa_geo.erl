%%%-------------------------------------------------------------------
%%% @doc Geografi murni (tanpa I/O): validasi koordinat, clamp radius,
%%% haversine, dan konversi angka. Dipisah dari {@link nusa_nearby} agar
%%% bisa di-unit-test tanpa MySQL/ETS.
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_geo).

-export([valid_coords/2, clamp_radius/1, haversine/4, round3/1, to_float/1]).

-define(EARTH_KM, 6371.0).
-define(MIN_RADIUS_KM, 1).
-define(MAX_RADIUS_KM, 100).
-define(DEFAULT_RADIUS_KM, 25).

%%%-------------------------------------------------------------------
%%% @doc Koordinat valid: angka dalam rentang lintang/bujur bumi.
%%% @end
%%%-------------------------------------------------------------------
-spec valid_coords(term(), term()) -> boolean().
valid_coords(Lat, Lng) when is_number(Lat), is_number(Lng) ->
    Lat >= -90 andalso Lat =< 90 andalso Lng >= -180 andalso Lng =< 180;
valid_coords(_, _) ->
    false.

%%%-------------------------------------------------------------------
%%% @doc Jepit radius ke 1..100 km (default 25 bila bukan angka).
%%% @end
%%%-------------------------------------------------------------------
-spec clamp_radius(term()) -> 1..100.
clamp_radius(R) when is_number(R) ->
    Rounded = round(R),
    min(?MAX_RADIUS_KM, max(?MIN_RADIUS_KM, Rounded));
clamp_radius(_) ->
    ?DEFAULT_RADIUS_KM.

%%%-------------------------------------------------------------------
%%% @doc Jarak great-circle dalam kilometer (haversine).
%%% @end
%%%-------------------------------------------------------------------
-spec haversine(number(), number(), number(), number()) -> float().
haversine(Lat1, Lng1, Lat2, Lng2) ->
    DLat = deg2rad(Lat2 - Lat1),
    DLng = deg2rad(Lng2 - Lng1),
    A = math:sin(DLat / 2) * math:sin(DLat / 2)
        + math:cos(deg2rad(Lat1)) * math:cos(deg2rad(Lat2))
        * math:sin(DLng / 2) * math:sin(DLng / 2),
    2 * ?EARTH_KM * math:asin(math:sqrt(A)).

-spec deg2rad(number()) -> float().
deg2rad(D) -> D * math:pi() / 180.

%%%-------------------------------------------------------------------
%%% @doc Bulatkan ke 3 desimal (±100 m, cukup untuk privasi + cache).
%%% @end
%%%-------------------------------------------------------------------
-spec round3(number()) -> float().
round3(X) -> round(X * 1000) / 1000.

%%%-------------------------------------------------------------------
%%% @doc Konversi longgar ke float; `undefined' bila tak terbaca.
%%% (Nilai MySQL bisa datang sebagai binary/integer.)
%%% @end
%%%-------------------------------------------------------------------
-spec to_float(term()) -> float() | undefined.
to_float(V) when is_float(V) -> V;
to_float(V) when is_integer(V) -> V * 1.0;
to_float(V) when is_binary(V) ->
    try binary_to_float(V)
    catch _:_ ->
        try binary_to_integer(V) * 1.0
        catch _:_ -> undefined
        end
    end;
to_float(_) -> undefined.

﻿module uPER2
open System
open System.Numerics
open FsUtils
open Antlr.Runtime
open Constraints

type uperRange<'a> =
    | Concrete      of 'a*'a    //[a, b]
    | NegInf        of 'a               //(-inf, b]
    | PosInf        of 'a               //[a, +inf)
    | Full                                      // (-inf, +inf)


let min a b = if a<b then a else b
let max a b = if a>b then a else b

let emptyTypeError l = raise(SemanticError(l, "The constraints defined for this type do not allow any value"))

let rec uperUnion r1 r2 =
    match r1,r2 with
    | (Full,_)                              -> Full
    | (PosInf(a), PosInf(b))                -> PosInf(min a b)
    | (PosInf(a), NegInf(b))                -> Full
    | (PosInf(a1), Concrete(a,b))           -> PosInf(min a1 a)
    | (NegInf(a), NegInf(b))                -> NegInf(max a b)
    | (NegInf(a), PosInf(b))                -> Full
    | (NegInf(a1), Concrete(a,b))           -> NegInf(max a1 b)
    | (Concrete(a1,b1), Concrete(a2,b2))    -> Concrete(min a1 a2, max b1 b2)
    | _                                     -> uperUnion r2 r1

let rec uperIntersection r1 r2 (l:SrcLoc) =
    match r1,r2 with
    | (Full,_)                      -> r2
    | (PosInf(a), PosInf(b))        -> PosInf(max a b)
    | (PosInf(a), NegInf(b))        -> if a<=b then Concrete(a,b) else emptyTypeError l
    | (PosInf(a1), Concrete(a,b))   -> if a1>b then emptyTypeError l
                                        elif a1<=a then r1 
                                        else Concrete(a1,b) 
    | (NegInf(a), NegInf(b))        -> NegInf(min a b)
    | (NegInf(a), PosInf(b))        -> if a>=b then Concrete(b,a) else emptyTypeError l
    | (NegInf(a1), Concrete(a,b))   -> if a1<a then emptyTypeError l
                                        elif a1<b then Concrete(a1,b)
                                        else r2
    | (Concrete(a1,b1), Concrete(a2,b2)) -> if a1<=a2 && a2<=b1 && b1<=b2 then Concrete(a2,b1)
                                            elif a2<=a1 && a1<=b2 && b2<=b1 then Concrete(a1, b2)
                                            elif a2<=a1 && b1<=b2 then r1
                                            elif a1<=a2 && b2<=b1 then r2
                                            else emptyTypeError l
    | _                             ->  uperIntersection r2 r1 l


let getRangeTypeConstraintUperRange (c:RangeTypeConstraint<'v1,'v1>) funcNext funcPrev (l:SrcLoc) =
    foldRangeTypeConstraint
        (fun r1 r2 b s      -> uperUnion r1 r2, s)
        (fun r1 r2 s        -> uperIntersection r1 r2 l, s)
        (fun r s            -> Full, s)       
        (fun r1 r2 s        -> r1, s)
        (fun r s            -> Full, s)       
        (fun r1 r2 s        -> Full, s)
        (fun v rv s         -> Concrete (v,v),s)
        (fun v1 v2  minIsIn maxIsIn s  ->
            let val1 = if minIsIn then v1 else (funcNext v1)
            let val2 = if maxIsIn then v2 else (funcPrev v2)
            Concrete(val1 , val2), s)
        (fun v1 minIsIn  s      -> 
            let val1 = if minIsIn then v1 else (funcNext v1)
            PosInf(val1) ,s )
        (fun v2 maxIsIn s      -> 
            let val2 = if maxIsIn then v2 else (funcPrev v2)
            NegInf(val2), s)
        c 
        0
    

let getIntTypeConstraintUperRange (cons:IntegerTypeConstraint list) (l:SrcLoc) =
    let getIntTypeConstraintUperRange (c:IntegerTypeConstraint) (l:SrcLoc) =
        getRangeTypeConstraintUperRange c (fun x -> x + 1I) (fun x -> x - 1I) l |> fst
    cons |> List.fold(fun s c -> uperIntersection s (getIntTypeConstraintUperRange c l) l) Full

let getRealTypeConstraintUperRange (cons:RealTypeConstraint list) (l:SrcLoc) =
    let getRealTypeConstraintUperRange (c:RealTypeConstraint) (l:SrcLoc) =
        getRangeTypeConstraintUperRange c id id  l |> fst
    cons |> List.fold(fun s c -> uperIntersection s (getRealTypeConstraintUperRange c l) l) Full


let getSizeableTypeConstraintUperRange (c:SizableTypeConstraint<'v>) funcGetLength (l:SrcLoc) =
    foldSizableTypeConstraint
        (fun r1 r2 b s      -> uperUnion r1 r2, s)
        (fun r1 r2 s        -> uperIntersection r1 r2 l, s)
        (fun r s            -> Full, s)       
        (fun r1 r2 s        -> r1, s)
        (fun r s            -> Full, s)       
        (fun r1 r2 s        -> Full, s)
        (fun v rv s         -> Concrete (funcGetLength v,funcGetLength v),s)
        (fun r1 r2 b s      -> uperUnion r1 r2, s)
        (fun r1 r2 s        -> uperIntersection r1 r2 l, s)
        (fun r s            -> Full, s)       
        (fun r1 r2 s        -> r1, s)
        (fun r s            -> Full, s)       
        (fun r1 r2 s        -> Full, s)
        (fun v rv s         -> Concrete (v,v),s)
        (fun v1 v2  minIsIn maxIsIn s  ->
            let val1 = if minIsIn then v1 else (v1+1u)
            let val2 = if maxIsIn then v2 else (v2-1u)
            Concrete(val1 , val2), s)
        (fun v1 minIsIn  s      -> 
            let val1 = if minIsIn then v1 else (v1+1u)
            PosInf(val1) ,s )
        (fun v2 maxIsIn s      -> 
            let val2 = if maxIsIn then v2 else (v2-1u)
            NegInf(val2), s)
        c 
        0 |> fst

let getSizeableUperRange (cons:SizableTypeConstraint<'v> list) funcGetLength (l:SrcLoc) =
    let getConUperRange (c:SizableTypeConstraint<'v>) (l:SrcLoc) =
        getSizeableTypeConstraintUperRange c  funcGetLength l 
    cons |> List.fold(fun s c -> uperIntersection s (getConUperRange c l) l) Full

let getOctetStringUperRange (cons:OctetStringConstraint list) (l:SrcLoc) =
    getSizeableUperRange cons (fun x -> uint32 x.Length) l

let getBitStringUperRange (cons:BitStringConstraint list) (l:SrcLoc) =
    getSizeableUperRange cons (fun x -> uint32 x.Length) l

let getSequenceOfUperRange (cons:SequenceOfConstraint list) (l:SrcLoc) =
    getSizeableUperRange cons (fun x -> uint32 x.Length) l


let getStringConstraintSizeUperRange (c:IA5StringConstraint) (l:SrcLoc) =
    foldStringTypeConstraint
        (fun r1 r2 b s      -> uperUnion r1 r2, s)
        (fun r1 r2 s        -> uperIntersection r1 r2 l, s)
        (fun r s            -> Full, s)       
        (fun r1 r2 s        -> r1, s)
        (fun r s            -> Full, s)       
        (fun r1 r2 s        -> Full, s)
        (fun v rv s         -> Concrete (uint32 v.Length, uint32 v.Length),s)
        
        (fun r1 r2 b s      -> uperUnion r1 r2, s)
        (fun r1 r2 s        -> uperIntersection r1 r2 l, s)
        (fun r s            -> Full, s)       
        (fun r1 r2 s        -> r1, s)
        (fun r s            -> Full, s)       
        (fun r1 r2 s        -> Full, s)
        (fun v rv s         -> Concrete (v,v),s)
        (fun v1 v2  minIsIn maxIsIn s  ->
            let val1 = if minIsIn then v1 else (v1+1u)
            let val2 = if maxIsIn then v2 else (v2-1u)
            Concrete(val1 , val2), s)
        (fun v1 minIsIn  s      -> 
            let val1 = if minIsIn then v1 else (v1+1u)
            PosInf(val1) ,s )
        (fun v2 maxIsIn s      -> 
            let val2 = if maxIsIn then v2 else (v2-1u)
            NegInf(val2), s)

        (fun r1 r2 b s      -> Full, s)
        (fun r1 r2 s        -> Full, s)
        (fun r s            -> Full, s)       
        (fun r1 r2 s        -> Full, s)
        (fun r s            -> Full, s)       
        (fun r1 r2 s        -> Full, s)
        (fun v rv s         -> Full,s)
        (fun v1 v2  minIsIn maxIsIn s  ->Full, s)
        (fun v1 minIsIn  s  -> Full ,s )
        (fun v2 maxIsIn s   -> Full, s)
        c 
        0 |> fst
        

let getSrtingSizeUperRange (cons:IA5StringConstraint list) (l:SrcLoc) =
    let getConUperRange (c:IA5StringConstraint) (l:SrcLoc) =
        getStringConstraintSizeUperRange c  l 
    cons |> List.fold(fun s c -> uperIntersection s (getConUperRange c l) l) Full


let getStringConstraintAlphabetUperRange (c:IA5StringConstraint) (l:SrcLoc) =
    let nextChar (c:System.Char) =
        System.Convert.ToChar(System.Convert.ToInt32(c)+1)
    let prevChar (c:System.Char) =
        System.Convert.ToChar(System.Convert.ToInt32(c)-1)
    
    foldStringTypeConstraint
        (fun r1 r2 b s      -> uperUnion r1 r2, s)
        (fun r1 r2 s        -> uperIntersection r1 r2 l, s)
        (fun r s            -> Full, s)       
        (fun r1 r2 s        -> r1, s)
        (fun r s            -> Full, s)       
        (fun r1 r2 s        -> Full, s)
        (fun v rv s         -> Full,s)
        
        (fun r1 r2 b s      -> Full, s)
        (fun r1 r2 s        -> Full, s)
        (fun r s            -> Full, s)       
        (fun r1 r2 s        -> Full, s)
        (fun r s            -> Full, s)       
        (fun r1 r2 s        -> Full, s)
        (fun v rv s         -> Full,s)
        (fun v1 v2  minIsIn maxIsIn s  ->Full, s)
        (fun v1 minIsIn  s  -> Full ,s )
        (fun v2 maxIsIn s   -> Full, s)

        (fun r1 r2 b s      -> uperUnion r1 r2, s)
        (fun r1 r2 s        -> uperIntersection r1 r2 l, s)
        (fun r s            -> Full, s)       
        (fun r1 r2 s        -> r1, s)
        (fun r s            -> Full, s)       
        (fun r1 r2 s        -> Full, s)
        (fun v rv s         -> 
            let charSet = v.ToCharArray() |> Seq.toList
            let ret =
                match charSet with
                | []        -> emptyTypeError l
                | x::xs     -> xs |> List.fold(fun st c -> uperUnion st (Concrete (c,c))) (Concrete (x,x))
            ret, s)
        (fun v1 v2  minIsIn maxIsIn s  ->
            let val1 = if minIsIn then v1 else (nextChar v1)
            let val2 = if maxIsIn then v2 else (prevChar v2)
            Concrete(val1 , val2), s)
        (fun v1 minIsIn  s      -> 
            let val1 = if minIsIn then v1 else (nextChar v1)
            PosInf(val1) ,s )
        (fun v2 maxIsIn s      -> 
            let val2 = if maxIsIn then v2 else (prevChar v2)
            NegInf(val2), s)

        c 
        0 |> fst

let getSrtingAlphaUperRange (cons:IA5StringConstraint list) (l:SrcLoc) =
    let getConUperRange (c:IA5StringConstraint) (l:SrcLoc) =
        getStringConstraintAlphabetUperRange c  l 
    cons |> List.fold(fun s c -> uperIntersection s (getConUperRange c l) l) Full

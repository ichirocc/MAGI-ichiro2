package com.magi.app.engine

class RuleWindow(val days: Int, val shift: Int, val minimum: Int)
class RuleCount(val shift: Int, val minimum: Int)
class RuleSequence(val shifts: IntArray)
class RuleGroupShift(val group: Int, val shift: Int, val min: Int, val max: Int)
class RulePair(val groupA: Int, val shiftA: Int, val groupB: Int, val shiftB: Int)

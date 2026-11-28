package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class BranchPolicyTest {
    private val user = BranchMessage(1,true,"question","complete","other")
    private val answer = BranchMessage(2,false,"answer","complete","chat")
    @Test fun textReplyCanBeRegenerated() {
        assertEquals(BranchPlan(1,"question"),BranchPolicy.plan(answer,user,null))
    }
    @Test fun editCreatesNewRequestWithoutMutatingOriginal() {
        assertEquals(BranchPlan(1,"revised"),BranchPolicy.plan(user,null," revised "))
        assertEquals("question",user.text)
    }
    @Test fun otherOutcomesAreNotRegenerable() {
        for (kind in listOf("other","native","agent","image"))
            assertNull(BranchPolicy.plan(answer.copy(kind=kind),user,null))
        assertNull(BranchPolicy.plan(answer.copy(state="stopped"),user,null))
        assertNull(BranchPolicy.plan(answer,null,null))
        assertNull(BranchPolicy.plan(answer,user.copy(id=3),null))
    }
    @Test fun invalidEditsRejected() {
        assertNull(BranchPolicy.plan(answer,user,"edit"))
        assertNull(BranchPolicy.plan(user,null," "))
        assertNull(BranchPolicy.plan(user,null,"x".repeat(2001)))
    }
}

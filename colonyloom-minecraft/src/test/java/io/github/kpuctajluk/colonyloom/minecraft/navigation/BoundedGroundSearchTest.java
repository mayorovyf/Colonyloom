package io.github.kpuctajluk.colonyloom.minecraft.navigation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BoundedGroundSearchTest {
    @Test void traversableSerpentineExhaustsNodesWithoutNegativeRouteProof() {
        var search=new BoundedGroundSearch();
        var terrain=new BoundedGroundSearch.Terrain() {
            public boolean standable(int x,int y,int z) {
                if(y!=0 || x<0 || x>128 || z<0 || z>128)return false;
                return z%2==0 || x==(z/2%2==0 ? 128 : 0);
            }
            public boolean transition(int fromX,int fromY,int fromZ,int x,int y,int z) {return true;}
            public int additionalCost(int x,int y,int z) {return 0;}
        };
        // A constructive cardinal route proves connectivity independently of bounded A*.
        int witnessNodes=0;
        for(int row=0;row<=64;row++) {
            for(int x=0;x<=128;x++) {
                assertTrue(terrain.standable(x,0,row*2),"Serpentine witness lost supported floor");
                witnessNodes++;
            }
            if(row<64) {
                assertTrue(terrain.standable(row%2==0 ? 128 : 0,0,row*2+1),"Serpentine witness lost connector");
                witnessNodes++;
            }
        }
        assertTrue(witnessNodes>BoundedGroundSearch.MAX_NODES,"Traversable witness does not exceed fixed capacity");
        search.begin(0,0,0,128,0,128);
        BoundedGroundSearch.Result result=BoundedGroundSearch.Result.PENDING;
        int portions=0;
        while(result==BoundedGroundSearch.Result.PENDING && portions++<=BoundedGroundSearch.MAX_NODES) {
            result=search.advance(terrain);
            assertTrue(search.lastExpansions()<=BoundedGroundSearch.MAX_EXPANSIONS,"Expansion portion exceeded fixed bound");
        }
        assertTrue(result==BoundedGroundSearch.Result.EXHAUSTED && search.nodeLimitHit()
                && search.nodeCount()==BoundedGroundSearch.MAX_NODES && search.found()<0,"Traversable cap exhaustion became a negative route proof");
        search.begin(0,0,0,1,0,0);
        assertTrue(search.advance(terrain)==BoundedGroundSearch.Result.FOUND && !search.nodeLimitHit(),"Reused query retained exhausted state");
    }

    @Test void finiteDisconnectedTerrainProducesProvenUnreachable() {
        var search=new BoundedGroundSearch();
        search.begin(0,0,0,2,0,0);
        var terrain=new BoundedGroundSearch.Terrain() {
            public boolean standable(int x,int y,int z) {return y==0 && z==0 && (x==0 || x==2);}
            public boolean transition(int fromX,int fromY,int fromZ,int x,int y,int z) {return true;}
            public int additionalCost(int x,int y,int z) {return 0;}
        };
        assertTrue(search.advance(terrain)==BoundedGroundSearch.Result.UNREACHABLE && !search.nodeLimitHit(),"Complete disconnected search was confused with capacity exhaustion");
    }
    @Test void retainedGoalStillSucceedsAfterOtherNodesWereRefusedAtCapacity() {
        var search=new BoundedGroundSearch();
        int target=BoundedGroundSearch.MAX_NODES-1;
        search.begin(0,0,0,target,0,0);
        var terrain=new BoundedGroundSearch.Terrain() {
            public boolean standable(int x,int y,int z) {return y==0 && x>=0 && x<=target+1 && z>=0 && z<=1 && (z==0 || x==target-1);}
            public boolean transition(int fromX,int fromY,int fromZ,int x,int y,int z) {return true;}
            public int additionalCost(int x,int y,int z) {return 0;}
        };
        BoundedGroundSearch.Result result=BoundedGroundSearch.Result.PENDING;
        for(int portion=0;portion<=BoundedGroundSearch.MAX_NODES/BoundedGroundSearch.MAX_EXPANSIONS && result==BoundedGroundSearch.Result.PENDING;portion++)result=search.advance(terrain);
        assertTrue(result==BoundedGroundSearch.Result.FOUND && search.nodeLimitHit() && search.nodeCount()==BoundedGroundSearch.MAX_NODES,
                "A retained valid route was discarded merely because an unrelated node exceeded capacity");
    }
}

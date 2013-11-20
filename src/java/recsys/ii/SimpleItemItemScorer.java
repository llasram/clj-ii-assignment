package recsys.ii;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import java.util.List;

import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;

import org.grouplens.lenskit.ItemScorer;
import org.grouplens.lenskit.basic.AbstractItemScorer;
import org.grouplens.lenskit.data.dao.UserEventDAO;
import org.grouplens.lenskit.knn.NeighborhoodSize;
import org.grouplens.lenskit.vectors.MutableSparseVector;

public class SimpleItemItemScorer extends AbstractItemScorer {
  private static class Vars {
    private static final String NS = "recsys.ii";
    private static final Var
      simpleItemItemScorer = RT.var(NS, "simple-item-item-scorer");
    static {
      RT.var("clojure.core", "require").invoke(Symbol.intern(NS));
    }
  }

  private final ItemScorer is;

  @Inject
  public SimpleItemItemScorer(SimpleItemItemModel m, UserEventDAO dao,
                              @NeighborhoodSize int nnbrs) {
    this.is = (ItemScorer) Vars.simpleItemItemScorer.invoke(m, dao, nnbrs);
  }

  @Override
  public void score(long user, @Nonnull MutableSparseVector scores) {
    is.score(user, scores);
  }
}

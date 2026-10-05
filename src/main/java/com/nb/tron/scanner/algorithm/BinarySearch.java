package com.nb.tron.scanner.algorithm;

import java.util.function.LongPredicate;

/**
 * 在有序区间中二分查找最后满足条件的位置
 *
 * <p>只负责缩小查找范围，具体比较方式由调用方提供。</p>
 * <p>
 * Author: bin jack
 * Date: 05.10.26
 */
public final class BinarySearch {

    private BinarySearch() {
    }

    /**
     * 左边已确认满足条件，右边已确认不满足条件；每次比较中点，缩小范围。
     *
     * <p>条件需要连续：左侧为 true，越过分界后全部为 false。
     * 左右边界已由调用方确认，本方法只判断中间位置；判断异常直接向外抛出。</p>
     * <pre>{@code
     * 左=1000，右=1010 → 中点1005相同 → 左改为1005
     * 左=1005，右=1010 → 中点1007相同 → 左改为1007
     * 左=1007，右=1010 → 中点1008不同 → 右改为1008
     * 左=1007，右=1008 → 只差一块，返回1007
     * }</pre>
     *
     * @param left 已确认满足条件的下界
     * @param right 已确认不满足条件的上界，必须大于 left
     * @param matches 判断指定位置是否满足条件
     * @return 最后满足条件的位置
     */
    public static long findLastMatch(long left, long right, LongPredicate matches) {
        while (right - left > 1) {
            long middle = left + (right - left) / 2;
            if (matches.test(middle)) {
                left = middle;
            } else {
                right = middle;
            }
        }
        return left;
    }
}

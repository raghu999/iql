/*
 * Copyright (C) 2014 Indeed Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
 package com.indeed.imhotep.ez;

/**
* @author jwolfe
*/
public class SingleStatReference implements StatReference {
    final int depth;
    private String stringRep;
    private final EZImhotepSession session;
    boolean valid = true;

    SingleStatReference(int depth, String stringRep, EZImhotepSession session) {
        this.depth = depth;
        this.stringRep = stringRep;
        this.session = session;
    }

    @Override
    public String toString() {
        if (valid) {
            return "reference("+stringRep+")";
        } else {
            return "invalid stat reference";
        }
    }

    @Override
    public double[] getGroupStats() {
        Stats.requireValid(this);
        long[] values = session.getGroupStats(depth);
        double[] realValues = new double[values.length];
        for(int i = 0; i < values.length; i++) {
            realValues[i] = values[i];
        }
        return realValues;
    }

    @Override
    public double getValue(long[] stats) {
        return (double)stats[depth];
    }

    @Override
    public boolean isValid() {
        return valid;
    }

    @Override
    public void invalidate() {
        valid = false;
    }
}

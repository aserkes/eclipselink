/*******************************************************************************
 * Copyright (c) 2020 IBM Corporation. All rights reserved.
 * This program and the accompanying materials are made available under the 
 * terms of the Eclipse Public License v1.0 and Eclipse Distribution License v. 1.0 
 * which accompanies this distribution. 
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at 
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * Contributors:
 *     01/06/2020 - Will Dazey
 *       - 347987: Fix Attribute Override for Complex Embeddables
 ******************************************************************************/
package org.eclipse.persistence.jpa.test.mapping.model;

import java.io.Serializable;

import javax.persistence.Column;
import javax.persistence.Embeddable;

@Embeddable
public class OverrideEmbeddableA implements Serializable{
    private static final long serialVersionUID = 1L;

    @Column(name = "VALUE")
    private Integer value;

    @Column(name = "VALUE2")
    private Integer value2;

    private OverrideNestedEmbeddableA nestedValue;

    public OverrideEmbeddableA() { }

    public OverrideEmbeddableA(Integer value, Integer value2, OverrideNestedEmbeddableA nestedValue) {
        this.value = value;
        this.value2 = value2;
        this.nestedValue = nestedValue;
    }
}

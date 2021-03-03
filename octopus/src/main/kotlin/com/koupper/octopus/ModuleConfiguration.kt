package com.koupper.octopus

import com.koupper.octopus.process.ModuleProcess

class ModuleConfiguration : ModuleProcess {
    lateinit var name: String
    private val constituents: MutableMap<String, Any> = mutableMapOf()

    override fun setup(name: String, metadata: Map<String, Any>): ModuleProcess {
        this.name = name
        this.constituents.putAll(constituents)

        return this
    }

    override fun moduleName(): String {
        return this.name
    }

    override fun build() {
        print("ahuevo perras");
    }
}

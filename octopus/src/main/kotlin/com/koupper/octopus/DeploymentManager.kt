package com.koupper.octopus

interface DeploymentManager {
    fun toDeployableJar(name: String): DeploymentManager
}